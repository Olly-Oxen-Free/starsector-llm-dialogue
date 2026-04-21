package starlogue.api;

import starlogue.personality.FactionProfile;

/**
 * Provides or overrides a {@link FactionProfile} for a given faction ID.
 * Checked when FactionProfileRegistry fails to find a built-in profile.
 *
 * <p>Return null to indicate this contributor has no profile for the
 * requested faction; the next contributor (or the built-in default)
 * will be tried.
 */
public interface FactionProfileContributor {
    String getModId();

    /**
     * @param factionId The faction ID being looked up.
     * @return A FactionProfile for this faction, or null to pass through.
     */
    FactionProfile getProfile(String factionId);
}
