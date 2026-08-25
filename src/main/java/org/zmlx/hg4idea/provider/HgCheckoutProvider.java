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
package org.zmlx.hg4idea.provider;

import consulo.annotation.component.ExtensionImpl;
import consulo.disposer.Disposable;
import consulo.document.FileDocumentManager;
import consulo.localize.LocalizeValue;
import consulo.mercurial.localize.HgLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.checkout.CheckoutPage;
import consulo.versionControlSystem.checkout.CheckoutProvider;
import consulo.versionControlSystem.distributed.localize.DistributedVcsLocalize;
import jakarta.annotation.Nonnull;

@ExtensionImpl
public class HgCheckoutProvider implements CheckoutProvider {
    @Nonnull
    @Override
    public LocalizeValue getName() {
        return HgLocalize.hg4ideaMercurial();
    }

    @Override
    public LocalizeValue getActionName() {
        return DistributedVcsLocalize.cloneButton();
    }

    @Override
    @RequiredUIAccess
    public CheckoutPage createPage(@Nonnull Project project, @Nonnull Disposable uiDisposable) {
        FileDocumentManager.getInstance().saveAllDocuments();

        return new HgCheckoutPage(project, uiDisposable);
    }
}
