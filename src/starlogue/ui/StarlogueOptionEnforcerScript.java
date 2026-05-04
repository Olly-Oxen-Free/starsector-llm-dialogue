package starlogue.ui;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import org.json.JSONObject;
import starlogue.debug.DebugSessionLog;

/**
 * Fills the gap when {@code data/campaign/rules.csv} + {@code PopulateOptions} does not
 * run (notably portside bar {@code BarEventDialogPlugin}, and a few other UI paths).
 * <p>
 * The insert logic is the same as {@link AddStarlogueOptionCmd} with a {@code null} rules
 * map; throttled. Skips refit/inventory-style dialogs to avoid the vanilla UI bugs that
 * made {@code reportShownInteractionDialog} unsafe in the past.
 */
public class StarlogueOptionEnforcerScript implements EveryFrameScript {

    private float acc = 0f;

    @Override
    public void advance(float amount) {
        acc += amount;
        if (acc < 0.25f) return;
        acc = 0f;
        try {
            CampaignUIAPI ui = Global.getSector() != null ? Global.getSector().getCampaignUI() : null;
            if (ui == null) return;
            InteractionDialogAPI d = ui.getCurrentInteractionDialog();
            if (d == null) return;
            InteractionDialogPlugin p = d.getPlugin();
            if (p == null) return;
            String cn = p.getClass().getName();
            String low = cn.toLowerCase();
            boolean isRuleBased = p instanceof com.fs.starfarer.api.campaign.RuleBasedDialog;
            // Avoid refit / ship-customisation UIs — but do not skip bar plugins whose
            // FQN happens to contain "refit" as a substring unrelated to ship refit.
            if (low.contains("refit") && !low.contains("barevent") && !low.contains("bar_event")) {
                return;
            }
            boolean barLike = low.contains("bar") || low.contains("barevent") || low.contains("bar_event");
            boolean commLike = low.contains("commdirectory") || low.contains("comm_directory")
                || low.contains("commcontact") || low.contains("comm_contact")
                || low.contains("contactdialog") || low.contains("contact_dialog");
            // For non-bar/comm rule-based dialogs PopulateOptions handles injection — skip.
            // For bar/comm rule-based dialogs, bar event rules often clearOptions() after
            // PopulateOptions fires, wiping the injected option.  Let the enforcer supplement
            // even rule-based bar/comm UIs; the hasOption guard in AddStarlogueOptionCmd
            // prevents duplicates when the option is already present.
            if (!barLike && !commLike) return;
            // #region agent log
            try {
                if (barLike || commLike) {
                    JSONObject o = new JSONObject();
                    o.put("plugin", cn);
                    o.put("barLike", barLike);
                    o.put("commLike", commLike);
                    DebugSessionLog.log("H_ENF", "StarlogueOptionEnforcerScript.advance", "non-rule inject tick", o.toString());
                }
            } catch (Throwable ignore) { }
            // #endregion
            AddStarlogueOptionCmd.injectForBarOrNonRuleDialog(d);
        } catch (Throwable ignored) { }
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }
}
