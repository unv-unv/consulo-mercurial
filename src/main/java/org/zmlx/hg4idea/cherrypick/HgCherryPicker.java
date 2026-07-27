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
package org.zmlx.hg4idea.cherrypick;

import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.VcsCherryPicker;
import consulo.versionControlSystem.log.VcsFullCommitDetails;
import consulo.versionControlSystem.update.UpdatedFiles;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import org.zmlx.hg4idea.HgVcs;
import org.zmlx.hg4idea.action.HgCommandResultNotifier;
import org.zmlx.hg4idea.command.HgGraftCommand;
import org.zmlx.hg4idea.execution.HgCommandResult;
import org.zmlx.hg4idea.provider.update.HgConflictResolver;
import org.zmlx.hg4idea.repo.HgRepository;
import org.zmlx.hg4idea.repo.HgRepositoryManager;
import org.zmlx.hg4idea.util.HgErrorUtil;
import org.zmlx.hg4idea.util.HgUtil;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class HgCherryPicker extends VcsCherryPicker {

  @Nonnull
  private final Project myProject;

  public HgCherryPicker(@Nonnull Project project) {
    myProject = project;
  }

  @Nonnull
  @Override
  public VcsKey getSupportedVcs() {
    return HgVcs.getKey();
  }

  @Nonnull
  @Override
  public String getActionTitle() {
    return "Graft";
  }

  @Override
  public void cherryPick(@Nonnull final List<VcsFullCommitDetails> commits) {
    Map<HgRepository, List<VcsFullCommitDetails>> commitsInRoots = DvcsUtil.groupCommitsByRoots(
      HgUtil.getRepositoryManager(myProject), commits);
    for (Map.Entry<HgRepository, List<VcsFullCommitDetails>> entry : commitsInRoots.entrySet()) {
      processGrafting(entry.getKey(), ContainerUtil.map(entry.getValue(),
                                                        commitDetails -> commitDetails.getId().asString()));
    }
  }

  private static void processGrafting(@Nonnull HgRepository repository, @Nonnull List<String> hashes) {
    Project project = repository.getProject();
    VirtualFile root = repository.getRoot();
    HgGraftCommand command = new HgGraftCommand(project, repository);
    HgCommandResult result = command.startGrafting(hashes);
    boolean hasConflicts = HgConflictResolver.hasConflicts(project, root);
    if (!hasConflicts && HgErrorUtil.isCommandExecutionFailed(result)) {
      new HgCommandResultNotifier(project).notifyError(result, "Hg Error", "Couldn't  graft.");
      return;
    }
    final UpdatedFiles updatedFiles = UpdatedFiles.create();
    while (hasConflicts) {
      new HgConflictResolver(project, updatedFiles).resolve(root);
      hasConflicts = HgConflictResolver.hasConflicts(project, root);
      if (!hasConflicts) {
        result = command.continueGrafting();
        hasConflicts = HgConflictResolver.hasConflicts(project, root);
      }
      else {
        new HgCommandResultNotifier(project).notifyError(result, "Hg Error", "Couldn't continue grafting");
        break;
      }
    }
    repository.update();
    root.refresh(true, true);
  }

  @Override
  public boolean canHandleForRoots(@Nonnull Collection<VirtualFile> roots) {
    HgRepositoryManager hgRepositoryManager = HgUtil.getRepositoryManager(myProject);
    return roots.stream().allMatch(r -> hgRepositoryManager.getRepositoryForRootQuick(r) != null);
  }
}
