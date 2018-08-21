package io.ipoli.android.quest.schedule.today

import io.ipoli.android.common.AppState
import io.ipoli.android.common.BaseViewStateReducer
import io.ipoli.android.common.redux.Action
import io.ipoli.android.common.redux.BaseViewState
import org.threeten.bp.LocalDate

sealed class TodayAction : Action {
    data class Load(val today: LocalDate) : TodayAction()
}

object TodayReducer : BaseViewStateReducer<TodayViewState>() {

    override val stateKey = key<TodayViewState>()

    override fun reduce(state: AppState, subState: TodayViewState, action: Action) =
        when (action) {
            else -> subState
        }

    override fun defaultState() = TodayViewState(TodayViewState.StateType.LOADING)

}

data class TodayViewState(val type: StateType) : BaseViewState() {

    enum class StateType {
        LOADING,
        DATA_CHANGED
    }
}