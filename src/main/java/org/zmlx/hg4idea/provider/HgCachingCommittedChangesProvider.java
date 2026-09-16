// Copyright 2008-2010 Victor Iacoban
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under
// the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
// either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
package org.zmlx.hg4idea.provider;

import consulo.application.util.function.AsynchConsumer;
import consulo.localize.LocalizeValue;
import consulo.mercurial.localize.HgLocalize;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.awt.CopyPasteManager;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Comparing;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.*;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangeList;
import consulo.versionControlSystem.change.commited.DecoratorManager;
import consulo.versionControlSystem.change.commited.VcsCommittedListsZipper;
import consulo.versionControlSystem.change.commited.VcsCommittedViewAuxiliary;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.versionControlSystem.versionBrowser.ChangeBrowserSettings;
import consulo.versionControlSystem.versionBrowser.CommittedChangeList;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.zmlx.hg4idea.*;
import org.zmlx.hg4idea.command.HgLogCommand;
import org.zmlx.hg4idea.util.HgUtil;

import java.awt.datatransfer.StringSelection;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class HgCachingCommittedChangesProvider implements CachingCommittedChangesProvider<CommittedChangeList, ChangeBrowserSettings> {
    private final Project project;
    private final HgVcs myVcs;
    public final static int VERSION_WITH_REPOSITORY_BRANCHES = 2;

    public HgCachingCommittedChangesProvider(Project project, HgVcs vcs) {
        this.project = project;
        myVcs = vcs;
    }

    @Override
    public int getFormatVersion() {
        return VERSION_WITH_REPOSITORY_BRANCHES;
    }

    @Override
    public CommittedChangeList readChangeList(RepositoryLocation repositoryLocation, DataInput dataInput) throws IOException {
        HgRevisionNumber revision = HgRevisionNumber.getInstance(dataInput.readUTF(), dataInput.readUTF());
        String branch = dataInput.readUTF();
        String committerName = dataInput.readUTF();
        String comment = dataInput.readUTF();
        Date commitDate = new Date(dataInput.readLong());
        int changesCount = dataInput.readInt();
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < changesCount; i++) {
            HgContentRevision beforeRevision = readRevision(repositoryLocation, dataInput);
            HgContentRevision afterRevision = readRevision(repositoryLocation, dataInput);
            changes.add(new Change(beforeRevision, afterRevision));
        }
        return new HgCommittedChangeList(myVcs, revision, branch, comment, committerName, commitDate, changes);
    }

    @Override
    public void writeChangeList(DataOutput dataOutput, CommittedChangeList committedChangeList) throws IOException {
        HgCommittedChangeList changeList = (HgCommittedChangeList) committedChangeList;
        writeRevisionNumber(dataOutput, changeList.getRevisionNumber());
        dataOutput.writeUTF(changeList.getBranch());
        dataOutput.writeUTF(changeList.getCommitterName());
        dataOutput.writeUTF(changeList.getComment());
        dataOutput.writeLong(changeList.getCommitDate().getTime());
        dataOutput.writeInt(changeList.getChanges().size());
        for (Change change : changeList.getChanges()) {
            writeRevision(dataOutput, (HgContentRevision) change.getBeforeRevision());
            writeRevision(dataOutput, (HgContentRevision) change.getAfterRevision());
        }
    }

    private HgContentRevision readRevision(RepositoryLocation repositoryLocation, DataInput dataInput) throws IOException {
        String revisionPath = dataInput.readUTF();
        HgRevisionNumber revisionNumber = readRevisionNumber(dataInput);

        if (!StringUtil.isEmpty(revisionPath)) {
            VirtualFile root = ((HgRepositoryLocation) repositoryLocation).getRoot();
            return HgContentRevision.create(project, new HgFile(root, new File(revisionPath)), revisionNumber);
        }
        else {
            return null;
        }
    }

    private void writeRevision(DataOutput dataOutput, HgContentRevision revision) throws IOException {
        if (revision == null) {
            dataOutput.writeUTF("");
            writeRevisionNumber(dataOutput, HgRevisionNumber.getInstance("", ""));
        }
        else {
            dataOutput.writeUTF(revision.getFile().getIOFile().toString());
            writeRevisionNumber(dataOutput, revision.getRevisionNumber());
        }
    }

    private HgRevisionNumber readRevisionNumber(DataInput dataInput) throws IOException {
        String revisionRevision = dataInput.readUTF();
        String revisionChangeset = dataInput.readUTF();
        return HgRevisionNumber.getInstance(revisionRevision, revisionChangeset);
    }

    private void writeRevisionNumber(DataOutput dataOutput, HgRevisionNumber revisionNumber) throws IOException {
        dataOutput.writeUTF(revisionNumber.getRevision());
        dataOutput.writeUTF(revisionNumber.getChangeset());
    }

    @Override
    public boolean isMaxCountSupported() {
        return true;
    }

    @Override
    public Collection<FilePath> getIncomingFiles(RepositoryLocation repositoryLocation) throws VcsException {
        return null;
    }

    @Override
    public boolean refreshCacheByNumber() {
        return true;
    }

    @Override
    public String getChangelistTitle() {
        return null;
    }

    @Override
    public boolean isChangeLocallyAvailable(
        FilePath filePath,
        @Nullable VcsRevisionNumber localRevision,
        VcsRevisionNumber changeRevision,
        CommittedChangeList committedChangeList
    ) {
        return localRevision != null && localRevision.compareTo(changeRevision) >= 0;
    }

    @Override
    public boolean refreshIncomingWithCommitted() {
        return false;
    }

    @Override
    @Nonnull
    public ChangeBrowserSettings createDefaultSettings() {
        return new ChangeBrowserSettings();
    }

    @Override
    @Nullable
    public RepositoryLocation getLocationFor(FilePath filePath) {
        VirtualFile repo = VcsUtil.getVcsRootFor(project, filePath);
        if (repo == null) {
            return null;
        }
        return new HgRepositoryLocation(repo.getUrl(), repo);
    }

    @Override
    public RepositoryLocation getLocationFor(FilePath root, String repositoryPath) {
        return getLocationFor(root);
    }

    @Override
    @Nullable
    public VcsCommittedListsZipper getZipper() {
        return null;
    }

    @Override
    public void loadCommittedChanges(
        ChangeBrowserSettings changeBrowserSettings,
        RepositoryLocation repositoryLocation,
        int maxCount,
        AsynchConsumer<CommittedChangeList> consumer
    ) throws VcsException {
        try {
            List<CommittedChangeList> results = getCommittedChanges(changeBrowserSettings, repositoryLocation, maxCount);
            for (CommittedChangeList result : results) {
                consumer.accept(result);
            }
        }
        finally {
            consumer.finished();
        }
    }

    @Override
    public List<CommittedChangeList> getCommittedChanges(
        ChangeBrowserSettings changeBrowserSettings,
        RepositoryLocation repositoryLocation,
        int maxCount
    ) throws VcsException {
        VirtualFile root = ((HgRepositoryLocation) repositoryLocation).getRoot();

        HgFile hgFile = new HgFile(root, VcsUtil.getFilePath(root.getPath()));

        List<CommittedChangeList> result = new LinkedList<>();
        HgLogCommand hgLogCommand = new HgLogCommand(project);
        hgLogCommand.setLogFile(false);
        List<String> args = null;
        if (changeBrowserSettings != null) {
            HgLogArgsBuilder argsBuilder = new HgLogArgsBuilder(changeBrowserSettings);
            args = argsBuilder.getLogArgs();
            if (args.isEmpty()) {
                maxCount = maxCount == 0 ? VcsConfiguration.getInstance(project).MAXIMUM_HISTORY_ROWS : maxCount;
            }
        }
        List<HgFileRevision> localRevisions;
        localRevisions = hgLogCommand.execute(hgFile, maxCount == 0 ? -1 : maxCount, true, args);
        Collections.reverse(localRevisions);

        for (HgFileRevision revision : localRevisions) {
            HgRevisionNumber vcsRevisionNumber = revision.getRevisionNumber();
            List<HgRevisionNumber> parents = vcsRevisionNumber.getParents();

            HgRevisionNumber firstParent = parents.isEmpty() ? null : parents.get(0); // can have no parents if it is a root

            List<Change> changes = new ArrayList<>();
            for (String file : revision.getModifiedFiles()) {
                changes.add(createChange(root, file, firstParent, file, vcsRevisionNumber, FileStatus.MODIFIED));
            }
            for (String file : revision.getAddedFiles()) {
                changes.add(createChange(root, null, null, file, vcsRevisionNumber, FileStatus.ADDED));
            }
            for (String file : revision.getDeletedFiles()) {
                changes.add(createChange(root, file, firstParent, null, vcsRevisionNumber, FileStatus.DELETED));
            }
            for (Map.Entry<String, String> copiedFile : revision.getMovedFiles().entrySet()) {
                changes
                    .add(createChange(
                        root,
                        copiedFile.getKey(),
                        firstParent,
                        copiedFile.getValue(),
                        vcsRevisionNumber,
                        HgChangeProvider.RENAMED
                    ));
            }

            result.add(new HgCommittedChangeList(
                myVcs,
                vcsRevisionNumber,
                revision.getBranchName(),
                revision.getCommitMessage(),
                revision.getAuthor(),
                revision.getRevisionDate(),
                changes
            ));
        }
        Collections.reverse(result);
        return result;
    }

    private Change createChange(
        VirtualFile root,
        @Nullable String fileBefore,
        @Nullable HgRevisionNumber revisionBefore,
        @Nullable String fileAfter,
        HgRevisionNumber revisionAfter,
        FileStatus aStatus
    ) {

        HgContentRevision beforeRevision = fileBefore == null ? null
            : HgContentRevision.create(project, new HgFile(root, new File(root.getPath(), fileBefore)), revisionBefore);
        HgContentRevision afterRevision = fileAfter == null ? null
            : HgContentRevision.create(project, new HgFile(root, new File(root.getPath(), fileAfter)), revisionAfter);
        return new Change(beforeRevision, afterRevision, aStatus);
    }

    @Override
    public ChangeListColumn[] getColumns() {
        return new ChangeListColumn[]{BRANCH_COLUMN, ChangeListColumn.NUMBER, ChangeListColumn.DATE, ChangeListColumn.DESCRIPTION, ChangeListColumn.NAME};
    }

    @Override
    public VcsCommittedViewAuxiliary createActions(DecoratorManager decoratorManager, RepositoryLocation repositoryLocation) {
        AnAction copyHashAction = new AnAction("Copy &Hash", "Copy hash to clipboard", PlatformIconGroup.actionsCopy()) {
            @Override
            @RequiredUIAccess
            public void actionPerformed(AnActionEvent e) {
                ChangeList[] changeLists = e.getData(VcsDataKeys.CHANGE_LISTS);
                if (changeLists != null && changeLists[0] instanceof HgCommittedChangeList committedChangeList) {
                    HgRevisionNumber revisionNumber = committedChangeList.getRevisionNumber();
                    CopyPasteManager.getInstance().setContents(new StringSelection(revisionNumber.getChangeset()));
                }
            }
        };
        return new VcsCommittedViewAuxiliary(
            Collections.singletonList(copyHashAction),
            () -> {
            },
            Collections.singletonList(copyHashAction)
        );
    }

    @Override
    public int getUnlimitedCountValue() {
        return -1;
    }

    @Override
    public Pair<CommittedChangeList, FilePath> getOneList(VirtualFile file, VcsRevisionNumber number) throws VcsException {
        ChangeBrowserSettings settings = createDefaultSettings();
        settings.USE_CHANGE_AFTER_FILTER = true;
        settings.USE_CHANGE_BEFORE_FILTER = true;
        settings.CHANGE_AFTER = number.asString();
        settings.CHANGE_BEFORE = number.asString();
        // todo implement in proper way
        VirtualFile localVirtualFile = HgUtil.convertToLocalVirtualFile(file);
        if (localVirtualFile == null) {
            return null;
        }
        FilePath filePath = VcsUtil.getFilePath(localVirtualFile);
        CommittedChangeList list = getCommittedChangesForRevision(getLocationFor(filePath), number.asString());
        if (list != null) {
            return Pair.create(list, filePath);
        }
        return null;
    }

    @Override
    public RepositoryLocation getForNonLocal(VirtualFile file) {
        return null;
    }

    @Override
    public boolean supportsIncomingChanges() {
        return false;
    }

    @Nullable
    public CommittedChangeList getCommittedChangesForRevision(@Nullable RepositoryLocation repositoryLocation, String revision) {
        if (repositoryLocation == null) {
            return null;
        }
        VirtualFile root = ((HgRepositoryLocation) repositoryLocation).getRoot();
        HgFile hgFile = new HgFile(root, VcsUtil.getFilePath(root.getPath()));
        HgLogCommand hgLogCommand = new HgLogCommand(project);
        hgLogCommand.setLogFile(false);
        hgLogCommand.setFollowCopies(true);
        List<String> args = new ArrayList<>();
        args.add("--rev");
        args.add(revision);
        List<HgFileRevision> revisions;
        revisions = hgLogCommand.execute(hgFile, 1, true, args);
        if (ContainerUtil.isEmpty(revisions)) {
            return null;
        }
        HgFileRevision localRevision = revisions.get(0);
        HgRevisionNumber vcsRevisionNumber = localRevision.getRevisionNumber();
        List<HgRevisionNumber> parents = vcsRevisionNumber.getParents();
        HgRevisionNumber firstParent = parents.isEmpty() ? null : parents.get(0); // can have no parents if it is a root
        List<Change> changes = new ArrayList<>();
        for (String file : localRevision.getModifiedFiles()) {
            changes.add(createChange(root, file, firstParent, file, vcsRevisionNumber, FileStatus.MODIFIED));
        }
        for (String file : localRevision.getAddedFiles()) {
            changes.add(createChange(root, null, null, file, vcsRevisionNumber, FileStatus.ADDED));
        }
        for (String file : localRevision.getDeletedFiles()) {
            changes.add(createChange(root, file, firstParent, null, vcsRevisionNumber, FileStatus.DELETED));
        }
        for (Map.Entry<String, String> copiedFile : localRevision.getMovedFiles().entrySet()) {
            changes.add(createChange(
                root,
                copiedFile.getKey(),
                firstParent,
                copiedFile.getValue(),
                vcsRevisionNumber,
                HgChangeProvider.RENAMED
            ));
        }

        return new HgCommittedChangeList(
            myVcs,
            vcsRevisionNumber,
            localRevision.getBranchName(),
            localRevision.getCommitMessage(),
            localRevision.getAuthor(),
            localRevision.getRevisionDate(),
            changes
        );
    }

    private static final Comparator<HgCommittedChangeList> BRANCH_COLUMN_COMPARATOR = (o1, o2) -> Comparing.compare(
        o1.getBranch(),
        o2.getBranch()
    );

    private static final ChangeListColumn<HgCommittedChangeList> BRANCH_COLUMN = new ChangeListColumn<>() {
        @Override
        public LocalizeValue getTitle() {
            return HgLocalize.hg4ideaChangelistColumnBranch();
        }

        @Override
        public Object getValue(HgCommittedChangeList changeList) {
            String branch = changeList.getBranch();
            return branch.isEmpty() ? "default" : branch;
        }

        @Nullable
        @Override
        public Comparator<HgCommittedChangeList> getComparator() {
            return BRANCH_COLUMN_COMPARATOR;
        }
    };

    private static class HgLogArgsBuilder {
        @Nonnull
        private final ChangeBrowserSettings myBrowserSettings;

        HgLogArgsBuilder(@Nonnull ChangeBrowserSettings browserSettings) {
            myBrowserSettings = browserSettings;
        }

        @Nonnull
        List<String> getLogArgs() {

            StringBuilder args = new StringBuilder();
            Date afterDate = myBrowserSettings.getDateAfter();
            Date beforeDate = myBrowserSettings.getDateBefore();
            Long afterFilter = myBrowserSettings.getChangeAfterFilter();
            Long beforeFilter = myBrowserSettings.getChangeBeforeFilter();

            SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");

            if ((afterFilter != null) && (beforeFilter != null)) {
                args.append(afterFilter).append(":").append(beforeFilter);
            }
            else if (afterFilter != null) {
                args.append("tip:").append(afterFilter);
            }
            else if (beforeFilter != null) {
                args.append("reverse(:").append(beforeFilter).append(")");
            }

            if (afterDate != null) {
                if (args.length() > 0) {
                    args.append(" and ");
                }
                args.append("date('>").append(dateFormatter.format(afterDate)).append("')");
            }

            if (beforeDate != null) {
                if (args.length() > 0) {
                    args.append(" and ");
                }

                args.append("date('<").append(dateFormatter.format(beforeDate)).append("')");
            }

            if (args.length() > 0) {
                List<String> logArgs = new ArrayList<>();
                logArgs.add("-r");
                logArgs.add(args.toString());
                return logArgs;
            }

            return Collections.emptyList();
        }
    }
}
