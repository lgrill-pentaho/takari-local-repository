package io.takari.aether.localrepo;

/*******************************************************************************
 * Copyright (c) 2010, 2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.SessionData;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.UpdateCheck;
import org.eclipse.aether.impl.UpdateCheckManager;
import org.eclipse.aether.impl.UpdatePolicyAnalyzer;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.AuthenticationDigest;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ResolutionErrorPolicy;
import org.eclipse.aether.resolution.ResolutionErrorPolicyRequest;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.ArtifactTransferException;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.eclipse.aether.transfer.MetadataTransferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named("takari")
@Singleton
public class TakariUpdateCheckManager implements UpdateCheckManager {

  private Logger logger = LoggerFactory.getLogger(TakariUpdateCheckManager.class);

  private static final String ERROR_FLAG = "maven.retryOnDownloadError";

  @Inject
  private UpdatePolicyAnalyzer updatePolicyAnalyzer;

  private static final String UPDATED_KEY_SUFFIX = ".lastUpdated";

  private static final String ERROR_KEY_SUFFIX = ".error";

  private static final String NOT_FOUND = "";

  private static final String SESSION_CHECKS = "updateCheckManager.checks";

  private final boolean allowImmediateRetryOfDownloadFailures;

  public TakariUpdateCheckManager() {    
    if (System.getProperty(ERROR_FLAG) != null) {
      this.allowImmediateRetryOfDownloadFailures = Boolean.getBoolean(ERROR_FLAG);
    } else {
      this.allowImmediateRetryOfDownloadFailures = false;
    }    
  }
      
  public UpdateCheckManager setUpdatePolicyAnalyzer(UpdatePolicyAnalyzer updatePolicyAnalyzer) {
    this.updatePolicyAnalyzer = updatePolicyAnalyzer;
    return this;
  }  
  
  
  @Override
  public void checkArtifact(RepositorySystemSession session, UpdateCheck<Artifact, ArtifactTransferException> check) {
    if (check.getLocalLastUpdated() != 0 && !isUpdatedRequired(session, check.getLocalLastUpdated(), check.getPolicy())) {
      if (logger.isDebugEnabled()) {
        logger.debug("Skipped remote request for " + check.getItem() + ", locally installed artifact up-to-date.");
      }

      check.setRequired(false);
      return;
    }

    Artifact artifact = check.getItem();
    RemoteRepository repository = check.getRepository();

    File artifactFile = check.getFile();
    if (artifactFile == null) {
      throw new IllegalArgumentException(String.format("The artifact '%s' has no file attached", artifact));
    }

    boolean fileExists = check.isFileValid() && artifactFile.exists();

    File touchFile = getTouchFile(artifact, artifactFile);
    Properties props = read(touchFile);

    String updateKey = getUpdateKey(session, artifactFile, repository);
    String dataKey = getDataKey(artifact, artifactFile, repository);

    String error = getError(props, dataKey);

    long lastUpdated;
    if (error == null) {
      if (fileExists) {
        // last update was successful
        lastUpdated = artifactFile.lastModified();
      } else {
        // this is the first attempt ever
        lastUpdated = 0;
      }
    } else if (error.length() <= 0) {
      // artifact did not exist
      lastUpdated = getLastUpdated(props, dataKey);
    } else {
      // artifact could not be transferred
      String transferKey = getTransferKey(session, artifact, artifactFile, repository);
      lastUpdated = getLastUpdated(props, transferKey);
    }

    if (lastUpdated == 0) {
        check.setRequired(true);
    } else if (isAlreadyUpdated(session.getData(), updateKey)) {
      if (logger.isDebugEnabled()) {
        logger.debug("Skipped remote request for " + check.getItem() + ", already updated during this session.");
      }

      check.setRequired(false);
      if (error != null) {
        check.setException(newException(error, artifact, repository));
      }
    } else if (isUpdatedRequired(session, lastUpdated, check.getPolicy())) {
      check.setRequired(true);
    } else if (fileExists) {
      if (logger.isDebugEnabled()) {
        logger.debug("Skipped remote request for " + check.getItem() + ", locally cached artifact up-to-date.");
      }

      check.setRequired(false);
    } else {
      int errorPolicy = getPolicy(session, artifact, repository);
      if (error == null || error.length() <= 0) {
        if ((errorPolicy & ResolutionErrorPolicy.CACHE_NOT_FOUND) != 0) {
          check.setRequired(false);
          check.setException(newException(error, artifact, repository));
        } else {
          check.setRequired(true);
        }
      } else {
        if ((errorPolicy & ResolutionErrorPolicy.CACHE_TRANSFER_ERROR) != 0) {
          check.setRequired(false);
          check.setException(newException(error, artifact, repository));
        } else {
          check.setRequired(true);
        }
      }
    }
  }

  private ArtifactTransferException newException(String error, Artifact artifact, RemoteRepository repository) {
    if (error == null || error.length() <= 0) {
      return new ArtifactNotFoundException(artifact, repository, "Failure to find " + artifact + " in " + repository.getUrl() + " was cached in the local repository, "
          + "resolution will not be reattempted until the update interval of " + repository.getId() + " has elapsed or updates are forced");
    } else {
      return new ArtifactTransferException(artifact, repository, "Failure to transfer " + artifact + " from " + repository.getUrl() + " was cached in the local repository, "
          + "resolution will not be reattempted until the update interval of " + repository.getId() + " has elapsed or updates are forced. Original error: " + error);
    }
  }

  @Override
  public void checkMetadata(RepositorySystemSession session, UpdateCheck<Metadata, MetadataTransferException> check) {
    if (check.getLocalLastUpdated() != 0 && !isUpdatedRequired(session, check.getLocalLastUpdated(), check.getPolicy())) {
      if (logger.isDebugEnabled()) {
        logger.debug("Skipped remote request for " + check.getItem() + ", locally installed metadata up-to-date.");
      }

      check.setRequired(false);
      return;
    }

    Metadata metadata = check.getItem();
    RemoteRepository repository = check.getRepository();

    File metadataFile = check.getFile();
    if (metadataFile == null) {
      throw new IllegalArgumentException(String.format("The metadata '%s' has no file attached", metadata));
    }

    boolean fileExists = check.isFileValid() && metadataFile.exists();

    File touchFile = getTouchFile(metadata, metadataFile);
    Properties props = read(touchFile);

    String updateKey = getUpdateKey(session, metadataFile, repository);
    String dataKey = getDataKey(metadata, metadataFile, check.getAuthoritativeRepository());

    String error = getError(props, dataKey);

    long lastUpdated;
    if (error == null) {
      if (fileExists) {
        // last update was successful
        lastUpdated = getLastUpdated(props, dataKey);
      } else {
        // this is the first attempt ever
        lastUpdated = 0;
      }
    } else if (error.length() <= 0) {
      // metadata did not exist
      lastUpdated = getLastUpdated(props, dataKey);
    } else {
      // metadata could not be transferred
      String transferKey = getTransferKey(session, metadata, metadataFile, repository);
      lastUpdated = getLastUpdated(props, transferKey);
    }

    if (lastUpdated == 0) {
        check.setRequired(true);
    } else if (isAlreadyUpdated(session.getData(), updateKey)) {
      if (logger.isDebugEnabled()) {
        logger.debug("Skipped remote request for " + check.getItem() + ", already updated during this session.");
      }

      check.setRequired(false);
      if (error != null) {
        check.setException(newException(error, metadata, repository));
      }
    } else if (isUpdatedRequired(session, lastUpdated, check.getPolicy())) {
      check.setRequired(true);
    } else if (fileExists) {
      if (logger.isDebugEnabled()) {
        logger.debug("Skipped remote request for " + check.getItem() + ", locally cached metadata up-to-date.");
      }

      check.setRequired(false);
    } else {
      int errorPolicy = getPolicy(session, metadata, repository);
      if (error == null || error.length() <= 0) {
        if ((errorPolicy & ResolutionErrorPolicy.CACHE_NOT_FOUND) != 0) {
          check.setRequired(false);
          check.setException(newException(error, metadata, repository));
        } else {
          check.setRequired(true);
        }
      } else {
        if ((errorPolicy & ResolutionErrorPolicy.CACHE_TRANSFER_ERROR) != 0) {
          check.setRequired(false);
          check.setException(newException(error, metadata, repository));
        } else {
          check.setRequired(true);
        }
      }
    }
  }

  private MetadataTransferException newException(String error, Metadata metadata, RemoteRepository repository) {
    if (error == null || error.length() <= 0) {
      return new MetadataNotFoundException(metadata, repository, "Failure to find " + metadata + " in " + repository.getUrl() + " was cached in the local repository, "
          + "resolution will not be reattempted until the update interval of " + repository.getId() + " has elapsed or updates are forced");
    } else {
      return new MetadataTransferException(metadata, repository, "Failure to transfer " + metadata + " from " + repository.getUrl() + " was cached in the local repository, "
          + "resolution will not be reattempted until the update interval of " + repository.getId() + " has elapsed or updates are forced. Original error: " + error);
    }
  }

  private long getLastUpdated(Properties props, String key) {
    String value = props.getProperty(key + UPDATED_KEY_SUFFIX, "");
    try {
      return (value.length() > 0) ? Long.parseLong(value) : 1;
    } catch (NumberFormatException e) {
      logger.debug("Cannot parse lastUpdated date: \'" + value + "\'. Ignoring.", e);
      return 1;
    }
  }

  private String getError(Properties props, String key) {
    return props.getProperty(key + ERROR_KEY_SUFFIX);
  }

  private File getTouchFile(Artifact artifact, File artifactFile) {
    return new File(artifactFile.getPath() + ".lastUpdated");
  }

  private File getTouchFile(Metadata metadata, File metadataFile) {
    return new File(metadataFile.getParent(), "resolver-status.properties");
  }

  private String getDataKey(Artifact artifact, File artifactFile, RemoteRepository repository) {
    Set<String> mirroredUrls = Collections.emptySet();
    if (repository.isRepositoryManager()) {
      mirroredUrls = new TreeSet<String>();
      for (RemoteRepository mirroredRepository : repository.getMirroredRepositories()) {
        mirroredUrls.add(normalizeRepoUrl(mirroredRepository.getUrl()));
      }
    }

    StringBuilder buffer = new StringBuilder(1024);

    buffer.append(normalizeRepoUrl(repository.getUrl()));
    for (String mirroredUrl : mirroredUrls) {
      buffer.append('+').append(mirroredUrl);
    }

    return buffer.toString();
  }

  private String getTransferKey(RepositorySystemSession session, Artifact artifact, File artifactFile, RemoteRepository repository) {
    return getRepoKey(session, repository);
  }

  private String getDataKey(Metadata metadata, File metadataFile, RemoteRepository repository) {
    return metadataFile.getName();
  }

  private String getTransferKey(RepositorySystemSession session, Metadata metadata, File metadataFile, RemoteRepository repository) {
    return metadataFile.getName() + '/' + getRepoKey(session, repository);
  }

  private String getRepoKey(RepositorySystemSession session, RemoteRepository repository) {
    StringBuilder buffer = new StringBuilder(128);

    Proxy proxy = repository.getProxy();
    if (proxy != null) {
      buffer.append(AuthenticationDigest.forProxy(session, repository)).append('@');
      buffer.append(proxy.getHost()).append(':').append(proxy.getPort()).append('>');
    }

    buffer.append(AuthenticationDigest.forRepository(session, repository)).append('@');

    buffer.append(repository.getContentType()).append('-');
    buffer.append(repository.getId() ).append( '-' );
    buffer.append(normalizeRepoUrl(repository.getUrl()));

    return buffer.toString();
  }

  private String normalizeRepoUrl(String url) {
    String result = url;
    if (url != null && url.length() > 0 && !url.endsWith("/")) {
      result = url + '/';
    }
    return result;
  }

  private String getUpdateKey(RepositorySystemSession session, File file, RemoteRepository repository) {
    return file.getAbsolutePath() + '|' + getRepoKey(session, repository);
  }

  private boolean isAlreadyUpdated(SessionData data, Object updateKey) {
    Object checkedFiles = data.get(SESSION_CHECKS);
    if (!(checkedFiles instanceof Map)) {
      return false;
    }
    return ((Map<?, ?>) checkedFiles).containsKey(updateKey);
  }

  @SuppressWarnings("unchecked")
  private void setUpdated(SessionData data, Object updateKey) {
    Object checkedFiles = data.get(SESSION_CHECKS);
    while (!(checkedFiles instanceof Map)) {
      Object old = checkedFiles;
      checkedFiles = new ConcurrentHashMap<Object, Object>(256);
      if (data.set(SESSION_CHECKS, old, checkedFiles)) {
        break;
      }
      checkedFiles = data.get(SESSION_CHECKS);
    }
    ((Map<Object, Boolean>) checkedFiles).put(updateKey, Boolean.TRUE);
  }

  private boolean isUpdatedRequired(RepositorySystemSession session, long lastModified, String policy) {
    return updatePolicyAnalyzer.isUpdatedRequired(session, lastModified, policy);
  }

  private Properties read(File touchFile) {
    Properties props = new TrackingFileManager().read(touchFile);
    return (props != null) ? props : new Properties();
  }

  @Override
  public void touchArtifact(RepositorySystemSession session, UpdateCheck<Artifact, ArtifactTransferException> check) {
    Artifact artifact = check.getItem();
    File artifactFile = check.getFile();
    File touchFile = getTouchFile(artifact, artifactFile);

    String updateKey = getUpdateKey(session, artifactFile, check.getRepository());
    String dataKey = getDataKey(artifact, artifactFile, check.getAuthoritativeRepository());
    String transferKey = getTransferKey(session, artifact, artifactFile, check.getRepository());

    setUpdated(session.getData(), updateKey);
    Properties props = write(touchFile, dataKey, transferKey, check.getException());

    if (artifactFile.exists() && !hasErrors(props)) {
      touchFile.delete();
    }
  }

  private boolean hasErrors(Properties props) {
    for (Object key : props.keySet()) {
      if (key.toString().endsWith(ERROR_KEY_SUFFIX)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void touchMetadata(RepositorySystemSession session, UpdateCheck<Metadata, MetadataTransferException> check) {
    Metadata metadata = check.getItem();
    File metadataFile = check.getFile();
    File touchFile = getTouchFile(metadata, metadataFile);

    String updateKey = getUpdateKey(session, metadataFile, check.getRepository());
    String dataKey = getDataKey(metadata, metadataFile, check.getAuthoritativeRepository());
    String transferKey = getTransferKey(session, metadata, metadataFile, check.getRepository());

    setUpdated(session.getData(), updateKey);
    write(touchFile, dataKey, transferKey, check.getException());
  }

  private Properties write(File touchFile, String dataKey, String transferKey, Exception error) {
    Map<String, String> updates = new HashMap<String, String>();

    String timestamp = Long.toString(System.currentTimeMillis());

    if (error == null) {
      updates.put(dataKey + ERROR_KEY_SUFFIX, null);
      updates.put(dataKey + UPDATED_KEY_SUFFIX, timestamp);
      updates.put(transferKey + UPDATED_KEY_SUFFIX, null);
    } else if (error instanceof ArtifactNotFoundException || error instanceof MetadataNotFoundException) {
      updates.put(dataKey + ERROR_KEY_SUFFIX, NOT_FOUND);
      updates.put(dataKey + UPDATED_KEY_SUFFIX, timestamp);
      updates.put(transferKey + UPDATED_KEY_SUFFIX, null);
    } else {
      String msg = error.getMessage();
      if (msg == null || msg.length() <= 0) {
        msg = error.getClass().getSimpleName();
      }
      updates.put(dataKey + ERROR_KEY_SUFFIX, msg);
      updates.put(dataKey + UPDATED_KEY_SUFFIX, null);
      updates.put(transferKey + UPDATED_KEY_SUFFIX, timestamp);
    }

    if (allowImmediateRetryOfDownloadFailures) {
      // Don't track the error so that the next time Maven fires up it will retry to download
      return new Properties();
    } else {
      return new TrackingFileManager().update(touchFile, updates);
    }
  }

  private static int getPolicy(RepositorySystemSession session, Artifact artifact, RemoteRepository repository) {
    ResolutionErrorPolicy rep = session.getResolutionErrorPolicy();
    if (rep == null) {
      return ResolutionErrorPolicy.CACHE_DISABLED;
    }
    return rep.getArtifactPolicy(session, new ResolutionErrorPolicyRequest<Artifact>(artifact, repository));
  }

  private static int getPolicy(RepositorySystemSession session, Metadata metadata, RemoteRepository repository) {
    ResolutionErrorPolicy rep = session.getResolutionErrorPolicy();
    if (rep == null) {
      return ResolutionErrorPolicy.CACHE_DISABLED;
    }
    return rep.getMetadataPolicy(session, new ResolutionErrorPolicyRequest<Metadata>(metadata, repository));
  }
}