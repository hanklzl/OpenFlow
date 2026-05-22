package com.hank.flow.open.ui.modeldownload

internal sealed class SelectionAction {
    object NoOp : SelectionAction()
    object PersistOnly : SelectionAction()
    object ConfirmDownload : SelectionAction()
}

internal fun decideSelection(
    candidateId: String,
    currentActiveId: String,
    installed: Boolean,
): SelectionAction = when {
    candidateId == currentActiveId -> SelectionAction.NoOp
    installed -> SelectionAction.PersistOnly
    else -> SelectionAction.ConfirmDownload
}
