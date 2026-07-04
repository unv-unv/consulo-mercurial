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
package org.zmlx.hg4idea.action;

import consulo.language.editor.CommonDataKeys;
import consulo.project.Project;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.AnActionWithSyncUpdate;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.util.lang.ObjectUtil;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import org.zmlx.hg4idea.HgVcs;
import org.zmlx.hg4idea.repo.HgRepository;
import org.zmlx.hg4idea.repo.HgRepositoryManager;
import org.zmlx.hg4idea.util.HgUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public abstract class HgAbstractGlobalAction extends DumbAwareAction implements AnActionWithSyncUpdate {
  public void actionPerformed(@Nonnull AnActionEvent event) {
    final Project project = event.getData(Project.KEY);
    if (project == null) {
      return;
    }
    VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    final HgRepositoryManager repositoryManager = HgUtil.getRepositoryManager(project);
    List<HgRepository> repositories = repositoryManager.getRepositories();
    if (!repositories.isEmpty()) {
      List<HgRepository> selectedRepositories = files != null
        ? HgActionUtil.collectRepositoriesFromFiles(repositoryManager, Arrays.asList(files))
        : List.of();

      execute(project, repositories,
              selectedRepositories.isEmpty() ? Collections.singletonList(HgUtil.getCurrentRepository(project)) : selectedRepositories);
    }
  }

  @Override
  public void update(AnActionEvent e) {
    boolean enabled = isEnabled(e);
    e.getPresentation().setEnabled(enabled);
  }

  protected abstract void execute(@Nonnull Project project,
                                  @Nonnull Collection<HgRepository> repositories,
                                  @Nonnull List<HgRepository> selectedRepositories);

  public boolean isEnabled(AnActionEvent e) {
    Project project = e.getData(CommonDataKeys.PROJECT);
    if (project == null) {
      return false;
    }
    HgVcs vcs = ObjectUtil.assertNotNull(HgVcs.getInstance(project));
    final VirtualFile[] roots = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs);
    if (roots == null || roots.length == 0) {
      return false;
    }
    return true;
  }
}
