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
package org.zmlx.hg4idea.action;

import consulo.language.editor.CommonDataKeys;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.util.collection.ContainerUtil;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.zmlx.hg4idea.repo.HgRepository;
import org.zmlx.hg4idea.repo.HgRepositoryManager;
import org.zmlx.hg4idea.util.HgUtil;

import java.util.Collection;
import java.util.List;

public class HgActionUtil {

  @Nonnull
  public static List<HgRepository> collectRepositoriesFromFiles(@Nonnull final HgRepositoryManager repositoryManager,
                                                                @Nonnull Collection<VirtualFile> files) {
    return ContainerUtil.mapNotNull(files, file -> repositoryManager.getRepositoryForFileQuick(file));
  }

  @Nullable
  @RequiredUIAccess
  public static HgRepository getSelectedRepositoryFromEvent(AnActionEvent e) {
    final Project project = e.getData(Project.KEY);
    if (project == null) {
      return null;
    }
    VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
    HgRepositoryManager repositoryManager = HgUtil.getRepositoryManager(project);
    return file != null ? repositoryManager.getRepositoryForFileQuick(file) : HgUtil.getCurrentRepository(project);
  }
}
