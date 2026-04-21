package starlogue.memory;

public enum MemoryEvent {
    HELPED_IN_BATTLE        ("helped_battle",       30,  90f),
    DEESCALATED             ("deescalated",         15,  60f),
    PAID_TRIBUTE            ("paid_tribute",        10,  45f),
    GAVE_WORK               ("gave_work",           20,  90f),
    SIGNED_AGREEMENT        ("signed_agreement",    25, 120f),
    POLITICALLY_ALLIED      ("politically_allied",  20,  90f),
    SHARED_INTEL            ("shared_intel",        10,  45f),
    OFFENDED                ("offended",           -20,  60f),
    LIED_DETECTED           ("lied_detected",      -30,  90f),
    BLUFF_CAUGHT            ("bluff_caught",       -25,  90f),
    HOSTILE_ACT             ("hostile_act",        -40, 120f),
    THREAT_FOLLOWED_THROUGH ("threat_followed",     20,  90f),
    EXTORTED                ("extorted",           -15,  60f),
    FACTION_BLUFF_CAUGHT    ("fac_bluff_caught",   -20,  90f),
    FACTION_THREAT_FOLLOWED ("fac_threat_follow",   15,  90f);

    public final String keySuffix;
    public final int points;
    public final float ttlDays;

    MemoryEvent(String keySuffix, int points, float ttlDays) {
        this.keySuffix = keySuffix;
        this.points = points;
        this.ttlDays = ttlDays;
    }
}
