// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.zmlx.hg4idea.status.ui;

import consulo.project.Project;
import consulo.project.ui.wm.StatusBarWidget;
import consulo.project.ui.wm.StatusBarWidgetFactory;
import consulo.ui.ex.popup.ListPopup;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.ui.DvcsStatusWidget;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.zmlx.hg4idea.HgProjectSettings;
import org.zmlx.hg4idea.HgStatusUpdater;
import org.zmlx.hg4idea.HgVcs;
import org.zmlx.hg4idea.branch.HgBranchPopup;
import org.zmlx.hg4idea.repo.HgRepository;
import org.zmlx.hg4idea.util.HgUtil;

/**
 * Widget to display basic hg status in the status bar.
 */
public class HgStatusWidget extends DvcsStatusWidget<HgRepository> {
    @Nonnull
    private final HgVcs myVcs;
    @Nonnull
    private final HgProjectSettings myProjectSettings;

    public HgStatusWidget(@Nonnull HgVcs vcs, @Nonnull Project project, @Nonnull StatusBarWidgetFactory factory, @Nonnull HgProjectSettings projectSettings) {
        super(project, factory, HgVcs.VCS_ID);
        myVcs = vcs;
        myProjectSettings = projectSettings;

        project.getMessageBus().connect(this).subscribe(HgStatusUpdater.class, (p, root) -> updateLater());
    }

    @Override
    public StatusBarWidget copy() {
        return new HgStatusWidget(myVcs, myProject, myFactory, myProjectSettings);
    }

    @Override
    protected HgRepository guessCurrentRepository(@Nonnull Project project, @Nullable VirtualFile selectedFile) {
        return DvcsUtil.guessWidgetRepository(project, HgUtil.getRepositoryManager(project),
            HgProjectSettings.getInstance(project).getRecentRootPath(), selectedFile);
    }

    @Nonnull
    @Override
    protected String getFullBranchName(@Nonnull HgRepository repository) {
        return HgUtil.getDisplayableBranchOrBookmarkText(repository);
    }

    @Override
    protected boolean isMultiRoot(@Nonnull Project project) {
        return HgUtil.getRepositoryManager(project).moreThanOneRoot();
    }

    @Nonnull
    @Override
    protected ListPopup getPopup(@Nonnull Project project, @Nonnull HgRepository repository) {
        return HgBranchPopup.getInstance(project, repository).asListPopup();
    }

    @Override
    protected void rememberRecentRoot(@Nonnull String path) {
        myProjectSettings.setRecentRootPath(path);
    }

}
