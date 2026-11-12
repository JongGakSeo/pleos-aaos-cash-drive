package ai.cashdrive.app.pleos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Gleo delivers an App Schema action as a broadcast whose action is the function name (the official
 * examples receive it with a BroadcastReceiver). This receiver hands the action to GleoActionService,
 * which reads the LOCAL_MOCK ledger and replies on APP_ACTION_RESPONSE. The receiver is exported
 * without a permission because Gleo documents none; the actions carry no parameters, the reply holds
 * only the mock balance, and nothing is written or credited on this path.
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != GleoActionService.CHECK_REWARD_BALANCE && action != GleoActionService.OPEN_KILOMOA) return
        val forward = Intent(context, GleoActionService::class.java).setAction(action)
            .putExtra(GleoActionService.PARAM_EXTRA, intent.getStringExtra(GleoActionService.PARAM_EXTRA) ?: "{}")
        try {
            context.startService(forward)
        } catch (e: IllegalStateException) {
            // Background start limits: the broadcast whitelist normally allows this; if not, Gleo times out.
            Log.w(TAG, "Could not start the action service for $action", e)
        }
    }

    private companion object { const val TAG = "CashDriveGleo" }
}
