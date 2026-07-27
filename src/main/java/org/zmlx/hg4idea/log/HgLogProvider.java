/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.zmlx.hg4idea.log;

import consulo.annotation.component.ExtensionImpl;
import consulo.component.messagebus.MessageBusConnection;
import consulo.disposer.Disposable;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Couple;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.log.*;
import consulo.versionControlSystem.log.util.UserNameRegex;
import consulo.versionControlSystem.log.util.VcsUserUtil;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import org.zmlx.hg4idea.HgNameWithHashInfo;
import org.zmlx.hg4idea.HgStatusUpdater;
import org.zmlx.hg4idea.HgVcs;
import org.zmlx.hg4idea.execution.HgCommandResult;
import org.zmlx.hg4idea.repo.HgConfig;
import org.zmlx.hg4idea.repo.HgRepository;
import org.zmlx.hg4idea.repo.HgRepositoryManager;
import org.zmlx.hg4idea.util.HgChangesetUtil;
import org.zmlx.hg4idea.util.HgUtil;
import org.zmlx.hg4idea.util.HgVersion;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;

import static org.zmlx.hg4idea.util.HgUtil.HEAD_REFERENCE;
import static org.zmlx.hg4idea.util.HgUtil.TIP_REFERENCE;

@ExtensionImpl
public class HgLogProvider implements VcsLogProvider {

  private static final Logger LOG = Logger.getInstance(HgLogProvider.class);

  @Nonnull
  private final Project myProject;
  @Nonnull
  private final HgRepositoryManager myRepositoryManager;
  @Nonnull
  private final VcsLogRefManager myRefSorter;
  @Nonnull
  private final VcsLogObjectsFactory myVcsObjectsFactory;

  @Inject
  public HgLogProvider(@Nonnull Project project, @Nonnull VcsLogObjectsFactory factory) {
    myProject = project;
    myRepositoryManager = HgRepositoryManager.getInstance(project);
    myRefSorter = new HgRefManager();
    myVcsObjectsFactory = factory;
  }

  @Nonnull
  @Override
  public DetailedLogData readFirstBlock(@Nonnull VirtualFile root,
                                        @Nonnull Requirements requirements) throws VcsException {
    List<VcsCommitMetadata> commits = HgHistoryUtil.loadMetadata(myProject, root, requirements.getCommitCount(),
                                                                 Collections.<String>emptyList());
    return new LogDataImpl(readAllRefs(root), commits);
  }

  @Override
  @Nonnull
  public LogData readAllHashes(@Nonnull VirtualFile root, @Nonnull final Consumer<TimedVcsCommit> commitConsumer) throws VcsException {
    Set<VcsUser> userRegistry = new HashSet<>();
    List<TimedVcsCommit> commits = HgHistoryUtil.readAllHashes(myProject, root, userRegistry::add, Collections.<String>emptyList());
    for (TimedVcsCommit commit : commits) {
      commitConsumer.accept(commit);
    }
    return new LogDataImpl(readAllRefs(root), userRegistry);
  }

  @Override
  public void readAllFullDetails(@Nonnull VirtualFile root, @Nonnull Consumer<VcsFullCommitDetails> commitConsumer) throws VcsException {
    readFullDetails(root, ContainerUtil.newArrayList(), commitConsumer);
  }

  @Override
  public void readFullDetails(@Nonnull VirtualFile root,
                              @Nonnull List<String> hashes,
                              @Nonnull Consumer<VcsFullCommitDetails> commitConsumer)
    throws VcsException {
    // this method currently is very slow and time consuming
    // so indexing is not to be used for mercurial for now
    HgVcs hgvcs = HgVcs.getInstance(myProject);
    assert hgvcs != null;
    final HgVersion version = hgvcs.getVersion();
    final String[] templates = HgBaseLogParser.constructFullTemplateArgument(true, version);

    HgCommandResult logResult = HgHistoryUtil.getLogResult(myProject, root, version, -1,
                                                           HgHistoryUtil.prepareHashes(hashes), HgChangesetUtil.makeTemplate(templates));
    if (logResult == null) return;
    if (!logResult.getErrorLines().isEmpty()) throw new VcsException(logResult.getRawError());
    HgHistoryUtil.createFullCommitsFromResult(myProject, root, logResult, version, false).forEach(commitConsumer::accept);
  }

  @Nonnull
  @Override
  public List<? extends VcsShortCommitDetails> readShortDetails(@Nonnull VirtualFile root, @Nonnull List<String> hashes)
    throws VcsException {
    return HgHistoryUtil.readMiniDetails(myProject, root, hashes);
  }

  @Nonnull
  @Override
  public List<? extends VcsFullCommitDetails> readFullDetails(@Nonnull VirtualFile root, @Nonnull List<String> hashes) throws VcsException {
    return HgHistoryUtil.history(myProject, root, -1, HgHistoryUtil.prepareHashes(hashes));
  }

  @Nonnull
  private Set<VcsRef> readAllRefs(@Nonnull VirtualFile root) throws VcsException {
    if (myProject.isDisposed()) {
      return Collections.emptySet();
    }
    HgRepository repository = myRepositoryManager.getRepositoryForRoot(root);
    if (repository == null) {
      LOG.error("Repository not found for root " + root);
      return Collections.emptySet();
    }

    repository.update();
    Map<String, LinkedHashSet<Hash>> branches = repository.getBranches();
    Set<String> openedBranchNames = repository.getOpenedBranches();
    Collection<HgNameWithHashInfo> bookmarks = repository.getBookmarks();
    Collection<HgNameWithHashInfo> tags = repository.getTags();
    Collection<HgNameWithHashInfo> localTags = repository.getLocalTags();
    Collection<HgNameWithHashInfo> mqAppliedPatches = repository.getMQAppliedPatches();

    Set<VcsRef> refs = new HashSet<>(branches.size() + bookmarks.size());

    for (Map.Entry<String, LinkedHashSet<Hash>> entry : branches.entrySet()) {
      String branchName = entry.getKey();
      boolean opened = openedBranchNames.contains(branchName);
      for (Hash hash : entry.getValue()) {
        refs.add(myVcsObjectsFactory.createRef(hash, branchName, opened ? HgRefManager.BRANCH : HgRefManager.CLOSED_BRANCH, root));
      }
    }

    for (HgNameWithHashInfo bookmarkInfo : bookmarks) {
      refs.add(myVcsObjectsFactory.createRef(bookmarkInfo.getHash(), bookmarkInfo.getName(),
                                             HgRefManager.BOOKMARK, root));
    }
    String currentRevision = repository.getCurrentRevision();
    if (currentRevision != null) { // null => fresh repository
      refs.add(myVcsObjectsFactory.createRef(myVcsObjectsFactory.createHash(currentRevision), HEAD_REFERENCE, HgRefManager.HEAD, root));
    }
    String tipRevision = repository.getTipRevision();
    if (tipRevision != null) { // null => fresh repository
      refs.add(myVcsObjectsFactory.createRef(myVcsObjectsFactory.createHash(tipRevision), TIP_REFERENCE, HgRefManager.TIP, root));
    }
    for (HgNameWithHashInfo tagInfo : tags) {
      refs.add(myVcsObjectsFactory.createRef(tagInfo.getHash(), tagInfo.getName(), HgRefManager.TAG, root));
    }
    for (HgNameWithHashInfo localTagInfo : localTags) {
      refs.add(myVcsObjectsFactory.createRef(localTagInfo.getHash(), localTagInfo.getName(),
                                             HgRefManager.LOCAL_TAG, root));
    }
    for (HgNameWithHashInfo mqPatchRef : mqAppliedPatches) {
      refs.add(myVcsObjectsFactory.createRef(mqPatchRef.getHash(), mqPatchRef.getName(),
                                             HgRefManager.MQ_APPLIED_TAG, root));
    }
    return refs;
  }

  @Nonnull
  @Override
  public VcsKey getSupportedVcs() {
    return HgVcs.getKey();
  }

  @Nonnull
  @Override
  public VcsLogRefManager getReferenceManager() {
    return myRefSorter;
  }

  @Nonnull
  @Override
  public Disposable subscribeToRootRefreshEvents(@Nonnull final Collection<VirtualFile> roots, @Nonnull final VcsLogRefresher refresher) {
    MessageBusConnection connection = myProject.getMessageBus().connect(myProject);
    connection.subscribe(HgStatusUpdater.class, (project, root) -> {
      if (root != null && roots.contains(root)) {
        refresher.refresh(root);
      }
    });
    return connection::disconnect;
  }

  @Nonnull
  @Override
  public List<TimedVcsCommit> getCommitsMatchingFilter(@Nonnull final VirtualFile root,
                                                       @Nonnull VcsLogFilterCollection filterCollection,
                                                       int maxCount) throws VcsException {
    List<String> filterParameters = ContainerUtil.newArrayList();

    // branch filter and user filter may be used several times without delimiter
    VcsLogBranchFilter branchFilter = filterCollection.getBranchFilter();
    if (branchFilter != null) {
      HgRepository repository = myRepositoryManager.getRepositoryForRoot(root);
      if (repository == null) {
        LOG.error("Repository not found for root " + root);
        return Collections.emptyList();
      }

      Collection<String> branchNames = repository.getBranches().keySet();
      Collection<String> bookmarkNames = HgUtil.getNamesWithoutHashes(repository.getBookmarks());
      Collection<String> predefinedNames = ContainerUtil.list(TIP_REFERENCE);

      boolean atLeastOneBranchExists = false;
      for (String branchName : ContainerUtil.concat(branchNames, bookmarkNames, predefinedNames)) {
        if (branchFilter.matches(branchName)) {
          filterParameters.add(HgHistoryUtil.prepareParameter("branch", branchName));
          atLeastOneBranchExists = true;
        }
      }

      if (branchFilter.matches(HEAD_REFERENCE)) {
        filterParameters.add(HgHistoryUtil.prepareParameter("branch", "."));
        filterParameters.add("-r");
        filterParameters.add("::."); //all ancestors for current revision;
        atLeastOneBranchExists = true;
      }

      if (!atLeastOneBranchExists) { // no such branches => filter matches nothing
        return Collections.emptyList();
      }
    }

    if (filterCollection.getUserFilter() != null) {
      filterParameters.add("-r");
      String authorFilter =
        StringUtil.join(ContainerUtil.map(ContainerUtil.map(filterCollection.getUserFilter().getUsers(root), VcsUserUtil::toExactString),
                                          UserNameRegex.EXTENDED_INSTANCE), "|");
      filterParameters.add("user('re:" + authorFilter + "')");
    }

    if (filterCollection.getDateFilter() != null) {
      StringBuilder args = new StringBuilder();
      final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
      filterParameters.add("-d");
      VcsLogDateFilter filter = filterCollection.getDateFilter();
      if (filter.getAfter() != null) {
        if (filter.getBefore() != null) {
          args.append(dateFormatter.format(filter.getAfter())).append(" to ").append(dateFormatter.format(filter.getBefore()));
        }
        else {
          args.append('>').append(dateFormatter.format(filter.getAfter()));
        }
      }

      else if (filter.getBefore() != null) {
        args.append('<').append(dateFormatter.format(filter.getBefore()));
      }
      filterParameters.add(args.toString());
    }

    if (filterCollection.getTextFilter() != null) {
      String textFilter = filterCollection.getTextFilter().getText();
      if (filterCollection.getTextFilter().isRegex()) {
        filterParameters.add("-r");
        filterParameters.add("grep(r'" + textFilter + "')");
      }
      else if (filterCollection.getTextFilter().matchesCase()) {
        filterParameters.add("-r");
        filterParameters.add("grep(r'" + StringUtil.escapeChars(textFilter, UserNameRegex.EXTENDED_REGEX_CHARS) + "')");
      }
      else {
        filterParameters.add(HgHistoryUtil.prepareParameter("keyword", textFilter));
      }
    }

    if (filterCollection.getStructureFilter() != null) {
      for (FilePath file : filterCollection.getStructureFilter().getFiles()) {
        filterParameters.add(file.getPath());
      }
    }

    return HgHistoryUtil.readAllHashes(myProject, root, vcsUser -> {
    }, filterParameters);
  }

  @Nullable
  @Override
  public VcsUser getCurrentUser(@Nonnull VirtualFile root) throws VcsException {
    String userName = HgConfig.getInstance(myProject, root).getNamedConfig("ui", "username");
    //order of variables to identify hg username see at mercurial/ui.py
    if (userName == null) {
      userName = System.getenv("HGUSER");
      if (userName == null) {
        userName = System.getenv("USER");
        if (userName == null) {
          userName = System.getenv("LOGNAME");
          if (userName == null) {
            return null;
          }
        }
      }
    }
    Couple<String> userArgs = HgUtil.parseUserNameAndEmail(userName);
    return myVcsObjectsFactory.createUser(userArgs.getFirst(), userArgs.getSecond());
  }

  @Nonnull
  @Override
  public Collection<String> getContainingBranches(@Nonnull VirtualFile root, @Nonnull Hash commitHash) throws VcsException {
    return HgHistoryUtil.getDescendingHeadsOfBranches(myProject, root, commitHash);
  }

  @Nullable
  @Override
  public String getCurrentBranch(@Nonnull VirtualFile root) {
    HgRepository repository = myRepositoryManager.getRepositoryForRootQuick(root);
    if (repository == null) return null;
    return repository.getCurrentBranchName();
  }

  @Nullable
  @Override
  public <T> T getPropertyValue(VcsLogProperties.VcsLogProperty<T> property) {
    if (property == VcsLogProperties.CASE_INSENSITIVE_REGEX) {
      return (T)Boolean.FALSE;
    }
    return null;
  }
}
