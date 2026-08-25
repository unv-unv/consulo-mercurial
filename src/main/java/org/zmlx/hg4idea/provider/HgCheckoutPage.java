/*
 * Copyright 2013-2026 consulo.io
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
package org.zmlx.hg4idea.provider;

import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.disposer.Disposable;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.checkout.CheckoutCallback;
import consulo.versionControlSystem.distributed.DvcsRememberedInputs;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.ui.DvcsCheckoutPage;
import jakarta.annotation.Nonnull;
import consulo.mercurial.localize.HgLocalize;
import org.zmlx.hg4idea.HgRememberedInputs;
import org.zmlx.hg4idea.HgVcs;
import org.zmlx.hg4idea.HgVcsMessages;
import org.zmlx.hg4idea.action.HgCommandResultNotifier;
import org.zmlx.hg4idea.command.HgCloneCommand;
import org.zmlx.hg4idea.command.HgIdentifyCommand;
import org.zmlx.hg4idea.execution.HgCommandResult;
import org.zmlx.hg4idea.util.HgErrorUtil;
import org.zmlx.hg4idea.util.HgUtil;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * @author VISTALL
 * @since 2026-08-25
 */
public class HgCheckoutPage extends DvcsCheckoutPage {
    public HgCheckoutPage(Project project, Disposable uiDisposable) {
        super(project, uiDisposable, HgLocalize.hg4ideaMercurial(), HgUtil.DOT_HG);
    }

    @Override
    protected DvcsRememberedInputs getRememberedInputs() {
        return HgRememberedInputs.getInstance();
    }

    @Override
    @RequiredUIAccess
    protected void test(String url, Consumer<Boolean> result) {
        Project project = myProject;

        new Task.Backgroundable(project, HgVcsMessages.message("hg4idea.clone.progress", url), true) {
            private boolean mySuccess;

            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                HgIdentifyCommand identifyCommand = new HgIdentifyCommand(project);
                identifyCommand.setSource(url);

                HgCommandResult commandResult = identifyCommand.execute(Application.get().getNoneModalityState());
                mySuccess = commandResult != null && commandResult.getExitValue() == 0;
            }

            @Override
            @RequiredUIAccess
            public void onSuccess() {
                result.accept(mySuccess);
            }
        }.queue();
    }

    @Override
    @RequiredUIAccess
    protected void doClone(CheckoutCallback callback, String sourceRepositoryUrl, String parentDirectory, String directoryName) {
        Project project = myProject;
        String targetDir = parentDirectory + File.separator + directoryName;

        AtomicReference<HgCommandResult> cloneResult = new AtomicReference<>();
        new Task.Backgroundable(project, HgVcsMessages.message("hg4idea.clone.progress", sourceRepositoryUrl), true) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                HgCloneCommand clone = new HgCloneCommand(project);
                clone.setRepositoryURL(sourceRepositoryUrl);
                clone.setDirectory(targetDir);
                cloneResult.set(clone.executeInCurrentThread());
            }

            @Override
            public void onSuccess() {
                if (cloneResult.get() == null || HgErrorUtil.hasErrorsInCommandExecution(cloneResult.get())) {
                    new HgCommandResultNotifier(project).notifyError(
                        cloneResult.get(),
                        "Clone failed",
                        "Clone from " + sourceRepositoryUrl + " failed."
                    );
                    return;
                }

                DvcsUtil.addMappingIfSubRoot(project, targetDir, HgVcs.VCS_ID);
                callback.directoryCheckedOut(new File(parentDirectory, directoryName), HgVcs.getKey());
                callback.checkoutCompleted();
            }
        }.queue();
    }
}
