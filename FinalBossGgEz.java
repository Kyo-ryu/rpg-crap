import java.util.Random;
import java.util.Scanner;

public class FinalBossGgEz {

    // ==================================================================
    // Shared tools and constants
    // ==================================================================
    static final Random RNG = new Random();
    static final Scanner SC = new Scanner(System.in);
    static final String LINE = "==========================================";

    static final String[] STAT_NAMES = { "Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Luck" };
    static final int STR = 0, DEX = 1, CON = 2, INT = 3, WIS = 4, LCK = 5;

    static final double BASE_CRIT_RATE = 0.01;   // 1/100 base crit chance
    static final int MAX_DAMAGE_REDUCTION = 10;  // cap on flat damage reduction
    static final int STARTING_GOLD = 50;
    static final int SHOP_SLOTS = 5;
    static final int BASIC_ITEMS = 14;           // first 14 entries in ITEMS: potions, throwables, cures, gadgets (books follow)
    static final int BOSS_INTERVAL = 3;          // the boss appears after this many cleared encounters

    // Damage over time
    static final int BLEED_START = 2;            // bleed: 2, 3, 4, ... per turn until cured
    static final int POISON_DAMAGE = 4;
    static final int BURN_DAMAGE = 3;
    static final int BURN_WEAK_BONUS = 5;        // extra burn damage on targets weak to fire
    static final int NATURAL_CURE_CHANCE = 20;   // % chance per turn that a status wears off
    static final int FROSTBITE_DAMAGE = 2;
    static final double FROSTBITE_DEX_CUT = 0.10; // frostbite lowers Dexterity by 10%
    static final int STATIC_DAMAGE = 1;           // static: -1 HP per turn
    static final int STATIC_PARALYZE_CHANCE = 30; // static: 30% chance to lose the turn

    // Smoke Bomb / Flashbomb
    static final int SMOKE_TURNS = 2;
    static final int SMOKE_COOLDOWN = 5;
    static final int FLASH_TURNS = 3;
    static final int FLASH_COOLDOWN = 5;
    static final double FLASH_ACCURACY_CUT = 0.30;

    // Arena effect (Calamitatum Rex): the element that is boosted for everyone, or null for none.
    static Element arenaEffect = null;

    static final double DODGE_EVASION_MULT = 3.0; // Dodge triples your evasion until your next turn
    static final int STAT_GAIN_MIN = 1;           // clearing a locale: +1..+6 on every stat
    static final int STAT_GAIN_MAX = 6;

    // Enemy scaling (see enemyDifficulty / scaleEnemy)
    static final double AVERAGE_ROLL_POWER = 48.65;    // combat power of an average starting roll
    static final double LEVEL_SCALING = 0.07;          // enemies get this much tougher per player level
    static final double ROLL_WEIGHT_START = 0.15;      // how strongly the starting roll bends enemy strength at level 1...
    static final double ROLL_WEIGHT_PER_LEVEL = 0.02;  // ...growing by this much per level...
    static final double ROLL_WEIGHT_MAX = 0.40;        // ...up to this cap
    static final double STAT_SCALE = 0.70;             // share of the HP scaling that also applies to enemy STR / CON

    // ==================================================================
    // Elements, statuses, positions
    // ==================================================================

    /** SLASH / PIERCE / BLUNT are physical damage types (Strength); FIRE / ICE / LIGHTNING are magical (Intelligence). */
    enum Element { SLASH, PIERCE, BLUNT, FIRE, ICE, LIGHTNING }

    enum Status { BLEED, POISON, BURN, FROSTBITE, STATIC }

    /** Special tags used by the boss to tie moves to its body parts. */
    enum MoveTag { NONE, BEAK, BITE, CLAWS, TAIL, TAIL_PIERCE, POUNCE, SLAM, DIVE, RAILGUN, BREATH }

    /** FRONT = high risk / high reward, BACK = safer but weaker. */
    enum Position {
        FRONT(1.20, 1.20, 0.00, "deal +20% damage, take +20% damage"),
        BACK(0.80, 0.80, 0.20, "deal -20% damage, take -20% damage, +20% retreat chance");

        final double damageDealtMult;
        final double damageTakenMult;
        final double retreatBonus;
        final String description;

        Position(double dealt, double taken, double retreat, String description) {
            this.damageDealtMult = dealt;
            this.damageTakenMult = taken;
            this.retreatBonus = retreat;
            this.description = description;
        }

        Position opposite() {
            return this == FRONT ? BACK : FRONT;
        }
    }

    // ==================================================================
    // Moves, weapons, armor, items
    // ==================================================================
    static class Move {
        final String name;
        final int power;
        final double hitChance;   // 0.0 - 1.0
        final Element element;    // null = no element
        final boolean magic;      // magic scales with Intelligence, physical with Strength
        final int uses;           // skills only: uses per battle
        final boolean charge;     // Charge: deals no damage, doubles the next attack
        final MoveTag tag;        // boss body-part link
        final int hitsMin;        // multi-hit moves strike hitsMin..hitsMax times
        final int hitsMax;
        final Status status;      // status inflicted on hit (or null)
        final double statusChance;
        final Status status2;     // optional second status (or null)
        final double statusChance2;

        Move(String name, int power, double hitChance, Element element, boolean magic, int uses, boolean charge,
             MoveTag tag, int hitsMin, int hitsMax, Status status, double statusChance,
             Status status2, double statusChance2) {
            this.name = name;
            this.power = power;
            this.hitChance = hitChance;
            this.element = element;
            this.magic = magic;
            this.uses = uses;
            this.charge = charge;
            this.tag = tag;
            this.hitsMin = hitsMin;
            this.hitsMax = hitsMax;
            this.status = status;
            this.statusChance = statusChance;
            this.status2 = status2;
            this.statusChance2 = statusChance2;
        }

        /** Move with one status. */
        Move(String name, int power, double hitChance, Element element, boolean magic, int uses, boolean charge,
             MoveTag tag, int hitsMin, int hitsMax, Status status, double statusChance) {
            this(name, power, hitChance, element, magic, uses, charge, tag, hitsMin, hitsMax, status, statusChance, null, 0.0);
        }

        /** Skills and Charge. */
        Move(String name, int power, double hitChance, Element element, boolean magic, int uses, boolean charge) {
            this(name, power, hitChance, element, magic, uses, charge, MoveTag.NONE, 1, 1, null, 0.0);
        }

        /** Plain weapon move. */
        Move(String name, int power, double hitChance, Element element) {
            this(name, power, hitChance, element, false, 0, false, MoveTag.NONE, 1, 1, null, 0.0);
        }

        /** Weapon move that can inflict a status. */
        Move(String name, int power, double hitChance, Element element, Status status, double statusChance) {
            this(name, power, hitChance, element, false, 0, false, MoveTag.NONE, 1, 1, status, statusChance);
        }
    }

    static class Weapon {
        final String name;
        final Element type; // primary physical damage type: SLASH, PIERCE or BLUNT (null = magic)
        final boolean ranged; // ranged weapons (bow, staff) ignore the Back position's damage-dealt penalty
        final int armorPen; // defense ignored per hit
        final int price;
        final Move[] moves;

        /** Melee weapon. */
        Weapon(String name, Element type, int armorPen, int price, Move... moves) {
            this(name, type, false, armorPen, price, moves);
        }

        Weapon(String name, Element type, boolean ranged, int armorPen, int price, Move... moves) {
            this.name = name;
            this.type = type;
            this.ranged = ranged;
            this.armorPen = armorPen;
            this.price = price;
            this.moves = moves;
        }

        String label() {
            return (type == null ? "MAGIC" : type.toString()) + (ranged ? ", RANGED" : "");
        }

        String moveList() {
            String text = "";
            for (int i = 0; i < moves.length; i++) {
                Move m = moves[i];
                text += (i > 0 ? ", " : "") + m.name + " (" + m.power + (m.element != null ? ", " + m.element : "") + ")";
            }
            return text;
        }
    }

    static class Armor {
        final String name;
        final int defense;
        final int damageReduction;
        final Element resist;     // null = none
        final boolean fireproof;  // immune to burning
        final boolean iceproof;   // immune to frostbite
        final boolean insulated;  // immune to static
        final int price;
        final int[] bonus;        // stat bonuses, indexed like STAT_NAMES

        Armor(String name, int defense, int damageReduction, Element resist, boolean fireproof, boolean iceproof,
              boolean insulated, int price, int[] bonus) {
            this.name = name;
            this.defense = defense;
            this.damageReduction = damageReduction;
            this.resist = resist;
            this.fireproof = fireproof;
            this.iceproof = iceproof;
            this.insulated = insulated;
            this.price = price;
            this.bonus = bonus;
        }

        String info() {
            String text = "DEF " + defense + ", DR " + damageReduction;
            if (resist != null) text += ", resists " + resist;
            if (fireproof) text += ", fireproof";
            if (iceproof) text += ", iceproof";
            if (insulated) text += ", insulated";
            for (int i = 0; i < bonus.length; i++) {
                if (bonus[i] != 0) text += String.format(", %+d %s", bonus[i], STAT_NAMES[i].substring(0, 3).toUpperCase());
            }
            return text;
        }
    }

    enum ItemType { HEAL, MAXHEAL, DAMAGE, ANTIDOTE, WATER, WARM, TRAP, SMOKE, FLASH, SPELLBOOK, SCROLL }

    static class Item {
        final String name;
        final ItemType type;
        final int value;      // heal amount / damage (unused otherwise)
        final int price;
        final int skillIndex; // books only: index into SKILLS, otherwise -1
        final Status status;  // thrown items: status that may be inflicted (or null)
        final double statusChance;

        Item(String name, ItemType type, int value, int price) {
            this(name, type, value, price, -1, null, 0.0);
        }

        Item(String name, ItemType type, int value, int price, int skillIndex) {
            this(name, type, value, price, skillIndex, null, 0.0);
        }

        /** Thrown item that may inflict a status. */
        Item(String name, ItemType type, int value, int price, Status status, double statusChance) {
            this(name, type, value, price, -1, status, statusChance);
        }

        Item(String name, ItemType type, int value, int price, int skillIndex, Status status, double statusChance) {
            this.name = name;
            this.type = type;
            this.value = value;
            this.price = price;
            this.skillIndex = skillIndex;
            this.status = status;
            this.statusChance = statusChance;
        }

        boolean isBook() {
            return type == ItemType.SPELLBOOK || type == ItemType.SCROLL;
        }
    }

    static int[] statBlock(int str, int dex, int con, int intel, int wis, int lck) {
        return new int[] { str, dex, con, intel, wis, lck };
    }

    static String describeItem(Item it, int bonus) {
        switch (it.type) {
            case HEAL: return "HEAL " + (it.value + bonus) + ", cures bleeding";
            case MAXHEAL: return "heals to full HP, cures bleeding";
            case DAMAGE:
                String text = "DAMAGE " + (it.value + bonus);
                if (it.status != null) text += ", " + (int) Math.round(it.statusChance * 100) + "% " + it.status.toString().toLowerCase();
                return text;
            case ANTIDOTE: return "cures poison";
            case WATER: return "cures burning";
            case WARM: return "cures frostbite and static";
            case TRAP: return "trap: the target loses its turn when it attacks in melee, 30% static";
            case SMOKE: return "every attack on you misses for " + SMOKE_TURNS + " turns (cooldown " + SMOKE_COOLDOWN + ")";
            case FLASH: return "enemy accuracy -30% for " + FLASH_TURNS + " turns (cooldown " + FLASH_COOLDOWN + ")";
            default: return "teaches " + SKILLS[it.skillIndex].name;
        }
    }

    // ==================================================================
    // Catalogs
    // ==================================================================

    // ---- "gg ez" version: one move that deals a flat 999 damage ----
    static final Move GG_EZ = new Move("gg ez", 999, 1.0, null);
    static final Weapon GG_EZ_WEAPON = new Weapon("Keyboard", null, true, 0, 0, GG_EZ);

    /** Charge is available to everyone: no damage, but the next attack that lands deals double. */
    static final Move CHARGE = new Move("Charge", 0, 1.0, null, false, 0, true);

    // Skills learned from spellbooks (magic) and scrolls (physical).
    static final Move[] SKILLS = {
        new Move("Fireball", 18, 0.85, Element.FIRE, true, 3, false),
        new Move("Frost Shard", 15, 0.90, Element.ICE, true, 3, false),
        new Move("Lightning Bolt", 20, 0.80, Element.LIGHTNING, true, 2, false),
        new Move("Whirlwind", 16, 0.85, Element.SLASH, false, 3, false),
        new Move("Piercing Lunge", 17, 0.85, Element.PIERCE, false, 3, false),
        new Move("Crushing Blow", 20, 0.75, Element.BLUNT, false, 2, false)
    };

    static final Item[] ITEMS = {
        new Item("Potion", ItemType.HEAL, 30, 20),
        new Item("Hi-Potion", ItemType.HEAL, 60, 45),
        new Item("Bomb", ItemType.DAMAGE, 25, 30),
        new Item("Antidote", ItemType.ANTIDOTE, 0, 25),
        new Item("Water Flask", ItemType.WATER, 0, 25),
        new Item("Warm Drink", ItemType.WARM, 0, 25),
        new Item("Max Potion", ItemType.MAXHEAL, 0, 90),
        new Item("Molotov Cocktail", ItemType.DAMAGE, 15, 35, Status.BURN, 0.30),
        new Item("Poisoned Gas Canister", ItemType.DAMAGE, 15, 35, Status.POISON, 0.30),
        new Item("Frozen Mist Canister", ItemType.DAMAGE, 15, 35, Status.FROSTBITE, 0.30),
        new Item("Throwing Knife", ItemType.DAMAGE, 15, 25, Status.BLEED, 0.30),
        new Item("Shock Trap", ItemType.TRAP, 0, 40),
        new Item("Smoke Bomb", ItemType.SMOKE, 0, 45),
        new Item("Flashbomb", ItemType.FLASH, 0, 45),
        new Item("Spellbook: Fireball", ItemType.SPELLBOOK, 0, 120, 0),
        new Item("Spellbook: Frost Shard", ItemType.SPELLBOOK, 0, 100, 1),
        new Item("Spellbook: Lightning Bolt", ItemType.SPELLBOOK, 0, 140, 2),
        new Item("Scroll: Whirlwind", ItemType.SCROLL, 0, 90, 3),
        new Item("Scroll: Piercing Lunge", ItemType.SCROLL, 0, 90, 4),
        new Item("Scroll: Crushing Blow", ItemType.SCROLL, 0, 100, 5)
    };

    static final Weapon[] WEAPONS = {
        new Weapon("Iron Sword", Element.SLASH, 1, 80,
                new Move("Slash", 10, 0.95, Element.SLASH),
                new Move("Thrust", 14, 0.90, Element.PIERCE),
                new Move("Cleave", 20, 0.80, Element.SLASH)),
        new Weapon("Hunter's Bow", Element.PIERCE, true, 2, 110,
                new Move("Quick Shot", 8, 1.00, Element.PIERCE),
                new Move("Aimed Shot", 16, 0.85, Element.PIERCE),
                new Move("Volley", 22, 0.75, Element.PIERCE)),
        new Weapon("Rusty Dagger", Element.PIERCE, 4, 70,
                new Move("Stab", 7, 1.00, Element.PIERCE),
                new Move("Backstab", 13, 0.90, Element.SLASH)),
        new Weapon("War Axe", Element.SLASH, 3, 130,
                new Move("Chop", 14, 0.90, Element.SLASH),
                new Move("Smash", 24, 0.70, Element.BLUNT)),
        new Weapon("Spiked Mace", Element.BLUNT, 2, 100,
                new Move("Bash", 12, 0.90, Element.BLUNT),
                new Move("Crush", 18, 0.78, Element.BLUNT),
                new Move("Skull Crack", 26, 0.70, Element.BLUNT)),
        new Weapon("Iron Spear", Element.PIERCE, 3, 100,
                new Move("Lunge", 11, 0.95, Element.PIERCE),
                new Move("Haft Strike", 9, 0.95, Element.BLUNT),
                new Move("Impale", 21, 0.75, Element.PIERCE)),
        // Magic staff: ranged, and its attacks are magic (they scale with Intelligence).
        new Weapon("Arcane Staff", null, true, 2, 120,
                new Move("Arcane Bolt", 10, 0.95, null, true, 0, false),
                new Move("Concentrated Beam", 18, 0.80, Element.PIERCE, true, 0, false), // magic-scaling pierce
                new Move("Galeslash", 14, 0.95, Element.SLASH, true, 0, false),          // magic-scaling slash
                new Move("Flame Burst", 15, 0.85, Element.FIRE, true, 0, false),
                new Move("Frost Spike", 15, 0.85, Element.ICE, true, 0, false),
                new Move("Spark", 14, 0.90, Element.LIGHTNING, true, 0, false)),
        new Weapon("Katana", Element.SLASH, 2, 160,
                new Move("Quick Draw", 12, 0.95, Element.SLASH),
                new Move("Crescent Cut", 17, 0.88, Element.SLASH),
                new Move("Moonfall", 25, 0.72, Element.SLASH)),
        new Weapon("Warhammer", Element.BLUNT, 4, 170,
                new Move("Pound", 16, 0.85, Element.BLUNT),
                new Move("Quake", 22, 0.75, Element.BLUNT),
                new Move("Earthshatter", 30, 0.60, Element.BLUNT)),
        new Weapon("Crossbow", Element.PIERCE, true, 4, 180,
                new Move("Bolt", 12, 0.95, Element.PIERCE),
                new Move("Heavy Bolt", 20, 0.82, Element.PIERCE),
                new Move("Armor Piercer", 26, 0.70, Element.PIERCE)),
        new Weapon("Crystal Wand", null, true, 1, 140,
                new Move("Crystal Shard", 12, 0.98, null, true, 0, false),
                new Move("Ember", 14, 0.92, Element.FIRE, true, 0, false),
                new Move("Frost Needle", 14, 0.92, Element.ICE, true, 0, false),
                new Move("Thunderclap", 18, 0.80, Element.LIGHTNING, true, 0, false)),
        new Weapon("Tempest Scepter", null, true, 3, 260,
                new Move("Gale Edge", 19, 0.90, Element.SLASH, true, 0, false),
                new Move("Piercing Ray", 22, 0.85, Element.PIERCE, true, 0, false),
                new Move("Storm Surge", 26, 0.75, Element.LIGHTNING, true, 0, false),
                new Move("Inferno", 24, 0.78, Element.FIRE, true, 0, false))
    };

    static final int SWORD_INDEX = 0;   // starting physical weapon (Iron Sword)
    static final int STAFF_INDEX = 6;   // starting magic weapon (Arcane Staff)

    static final Armor[] ARMORS = {
        new Armor("Leather Set", 2, 1, null, false, false, false, 60, statBlock(0, 1, 0, 0, 0, 0)),
        new Armor("Chainmail Set", 5, 3, Element.SLASH, false, false, false, 140, statBlock(0, 0, 1, 0, 0, 0)),
        new Armor("Ashen Mail", 6, 4, Element.FIRE, true, false, false, 200, statBlock(0, 0, 1, 0, 0, 0)),
        new Armor("Frostguard Mail", 7, 5, Element.ICE, false, true, false, 240, statBlock(0, 0, 2, 0, 0, 0)),
        new Armor("Stormguard Mail", 7, 5, Element.LIGHTNING, false, false, true, 250, statBlock(0, 1, 1, 0, 0, 0)),
        new Armor("Knight's Plate", 8, 6, Element.BLUNT, false, false, false, 260, statBlock(1, -1, 2, 0, 0, 0)),
        new Armor("Dragonscale Set", 12, 10, Element.FIRE, true, false, false, 520, statBlock(2, 0, 2, 1, 0, 0)),
        new Armor("Hunter's Hide", 3, 2, null, false, false, false, 100, statBlock(0, 2, 0, 0, 0, 0)),
        new Armor("Scale Mail", 6, 3, Element.PIERCE, false, false, false, 190, statBlock(0, 0, 1, 0, 0, 0)),
        new Armor("Mage Robes", 1, 0, null, false, false, false, 150, statBlock(0, 0, 0, 3, 1, 1)),
        new Armor("Warden Plate", 10, 7, null, false, false, false, 420, statBlock(1, -2, 2, 0, 0, 0))
    };

    // ---------------- Cockatrice (boss) moves ----------------
    static final Move PIERCE_STRIKE = new Move("Pierce Strike", 15, 0.90, Element.PIERCE, false, 0, false,
            MoveTag.BEAK, 1, 1, Status.BLEED, 0.10);
    static final Move RAGING_SWIPES = new Move("Raging Swipes", 5, 0.80, Element.SLASH, false, 0, false,
            MoveTag.CLAWS, 2, 6, null, 0.0);
    static final Move FLAMESHOT = new Move("Flameshot", 25, 0.75, Element.FIRE);
    static final Move FLAMETHROWER = new Move("Flamethrower", 15, 0.80, Element.FIRE);
    static final Move TAIL_SWIPE = new Move("Tail Swipe", 15, 0.90, Element.SLASH, false, 0, false,
            MoveTag.TAIL, 1, 1, Status.POISON, 0.20);
    static final Move POUNCE = new Move("Pounce", 22, 0.70, Element.SLASH);

    // ---------------- Forest enemies ----------------
    static Fighter[] spawnEncounter(EnemyType type, Player p) {
        int count = type.groupMin + (type.groupMax > type.groupMin ? RNG.nextInt(type.groupMax - type.groupMin + 1) : 0);
        int total = count + (type.escort != null ? (type.escortMin + (type.escortMax > type.escortMin ? RNG.nextInt(type.escortMax - type.escortMin + 1) : 0)) : 0);
        Fighter[] group = new Fighter[total];
        int idx = 0;
        for (int i = 0; i < count; i++) {
            String suffix = count > 1 ? String.valueOf((char)('A' + i)) : "";
            group[idx++] = type.spawn(suffix);
        }
        if (type.escort != null) {
            int escCount = total - count;
            for (int i = 0; i < escCount; i++) {
                String suffix = escCount > 1 ? String.valueOf((char)('A' + i)) : "";
                group[idx++] = type.escort.spawn(suffix);
            }
        }
        for (int i = 0; i < group.length; i++) {
            scaleEnemy(group[i], p);
        }
        return group;
    }

    // ------------------------------------------------------------------
    // Enemy scaling
    // ------------------------------------------------------------------

    /** Offense + toughness + speed from BASE scores (gear is left out, so buying armor stays rewarding). */
    static double combatPower(int[] scores) {
        return 2.0 * Math.max(scores[STR], scores[INT]) + 1.5 * scores[CON] + 0.5 * scores[DEX];
    }

    /** 1.0 = average starting roll, below 1.0 = poor roll, above 1.0 = great roll. */
    static double rollRating(int[] scores) {
        return combatPower(scores) / AVERAGE_ROLL_POWER;
    }

    static String rollLabel(double rating) {
        if (rating < 0.70) return "Poor";
        if (rating < 0.90) return "Below average";
        if (rating < 1.10) return "Average";
        if (rating < 1.30) return "Good";
        return "Excellent";
    }

    /**
     * Enemy strength multiplier for this player.
     *  - Level: enemies grow a little with every player level, so grinding never makes the game easier.
     *  - Starting roll: a good roll makes enemies somewhat stronger, a poor roll somewhat weaker. The effect starts
     *    small (good rolls enjoy an easier early game, poor rolls still struggle) and grows with level (good rolls
     *    can't stomp the late game, poor rolls get breathing room).
     */
    static double enemyDifficulty(Player p) {
        int level = p.level();
        double levelScale = 1.0 + LEVEL_SCALING * (level - 1);
        double weight = Math.min(ROLL_WEIGHT_MAX, ROLL_WEIGHT_START + ROLL_WEIGHT_PER_LEVEL * (level - 1));
        double rating = Math.max(0.35, Math.min(1.65, p.rollRating));
        return Math.max(0.5, levelScale * (1.0 + weight * (rating - 1.0)));
    }

    /** Scales a freshly spawned enemy or boss to the player. */
    static void scaleEnemy(Fighter e, Player p) {
        double k = enemyDifficulty(p);
        double statK = 1.0 + (k - 1.0) * STAT_SCALE;
        e.maxHp = Math.max(1, (int) Math.round(e.maxHp * k));
        e.hp = e.maxHp;
        e.scores[STR] = Math.max(1, (int) Math.round(e.scores[STR] * statK));
        e.scores[CON] = Math.max(1, (int) Math.round(e.scores[CON] * statK));
        e.goldReward = (int) Math.round(e.goldReward * (1.0 + (k - 1.0) * 0.5));
        if (e instanceof Boss) {
            for (BodyPart bp : ((Boss) e).parts) {
                bp.scale(k);
            }
        }
    }

    static class EnemyType {
        final String name;
        final int hp;
        final int[] stats;
        final int defense, damageReduction, armorPen;
        final Element weakness, resistance;
        final Weapon weapon;
        final int goldMin, goldMax;
        final int groupMin, groupMax;       // how many of this enemy appear together
        final EnemyType escort;             // optional followers that spawn with it
        final int escortMin, escortMax;

        EnemyType(String name, int hp, int[] stats, int defense, int damageReduction, int armorPen,
                  Element weakness, Element resistance, Weapon weapon, int goldMin, int goldMax) {
            this(name, hp, stats, defense, damageReduction, armorPen, weakness, resistance, weapon,
                    goldMin, goldMax, 1, 1, null, 0, 0);
        }

        EnemyType(String name, int hp, int[] stats, int defense, int damageReduction, int armorPen,
                  Element weakness, Element resistance, Weapon weapon, int goldMin, int goldMax,
                  int groupMin, int groupMax, EnemyType escort, int escortMin, int escortMax) {
            this.name = name;
            this.hp = hp;
            this.stats = stats;
            this.defense = defense;
            this.damageReduction = damageReduction;
            this.armorPen = armorPen;
            this.weakness = weakness;
            this.resistance = resistance;
            this.weapon = weapon;
            this.goldMin = goldMin;
            this.goldMax = goldMax;
            this.groupMin = groupMin;
            this.groupMax = groupMax;
            this.escort = escort;
            this.escortMin = escortMin;
            this.escortMax = escortMax;
        }

        Fighter spawn(String suffix) {
            Fighter f = new Fighter(suffix.isEmpty() ? name : name + " " + suffix, hp, stats.clone(), weapon);
            f.defenseBase = defense;
            f.damageReductionBase = damageReduction;
            f.armorPenBase = armorPen;
            f.weakness = weakness;
            f.resistance = resistance;
            f.goldReward = goldMin + RNG.nextInt(goldMax - goldMin + 1);
            return f;
        }
    }

    static final int RAPTOR_ALPHA_HP = 120;

    static final EnemyType SNAKE = new EnemyType("Snake", 85, statBlock(4, 15, 3, 2, 6, 8), 0, 0, 1,
            Element.BLUNT, null,
            new Weapon("Fangs", Element.PIERCE, 0, 0,
                    new Move("Bite", 9, 0.95, Element.PIERCE, Status.POISON, 0.30),
                    new Move("Fang Strike", 14, 0.85, Element.PIERCE, Status.POISON, 0.50)),
            8, 14, 1, 2, null, 0, 0);

    static final EnemyType GOBLIN = new EnemyType("Goblin", 100, statBlock(6, 12, 5, 5, 6, 12), 2, 0, 1,
            Element.LIGHTNING, null,
            new Weapon("Rusty Knife", Element.PIERCE, 0, 0,
                    new Move("Jab", 9, 0.95, Element.PIERCE),
                    new Move("Wild Swing", 15, 0.85, Element.SLASH)),
            10, 18);

    static final EnemyType SKELETON_ARCHER = new EnemyType("Skeleton Archer", 90, statBlock(7, 10, 8, 4, 6, 9), 1, 0, 3,
            Element.BLUNT, Element.PIERCE,
            new Weapon("Shortbow", Element.PIERCE, true, 0, 0,
                    new Move("Arrow", 10, 0.90, Element.PIERCE),
                    new Move("Piercing Shot", 17, 0.80, Element.PIERCE)),
            15, 25);

    static final EnemyType GREAT_APE = new EnemyType("Great Ape", 140, statBlock(10, 5, 12, 2, 5, 6), 8, 2, 0,
            Element.PIERCE, Element.BLUNT,
            new Weapon("Ape Fists", Element.BLUNT, 0, 0,
                    new Move("Pound", 14, 0.85, Element.BLUNT),
                    new Move("Slam", 22, 0.65, Element.BLUNT)),
            25, 40);

    // Raptors have 40% less HP than the Raptor Alpha that leads them.
    static final EnemyType RAPTOR = new EnemyType("Raptor", RAPTOR_ALPHA_HP * 6 / 10, statBlock(6, 15, 5, 3, 8, 10), 1, 0, 1,
            Element.ICE, null,
            new Weapon("Raptor Claws", Element.SLASH, 0, 0,
                    new Move("Claw Slash", 9, 0.95, Element.SLASH, Status.BLEED, 0.25),
                    new Move("Rending Pounce", 15, 0.75, Element.SLASH, Status.BLEED, 0.40)),
            8, 14);

    static final EnemyType RAPTOR_ALPHA = new EnemyType("Raptor Alpha", RAPTOR_ALPHA_HP, statBlock(7, 15, 6, 3, 8, 10), 1, 0, 1,
            Element.ICE, null,
            new Weapon("Alpha Claws", Element.SLASH, 0, 0,
                    new Move("Claw Slash", 10, 0.95, Element.SLASH, Status.BLEED, 0.30),
                    new Move("Rending Pounce", 16, 0.75, Element.SLASH, Status.BLEED, 0.45)),
            20, 32, 1, 1, RAPTOR, 1, 2);

    static final EnemyType[] FOREST = { SNAKE, GOBLIN, SKELETON_ARCHER, GREAT_APE, RAPTOR_ALPHA };

    // ---------------- Snowy Peaks enemies ----------------
    static final int DIRE_WOLF_ALPHA_HP = 150;

    static final EnemyType DIRE_WOLF = new EnemyType("Dire Wolf", 130, statBlock(9, 14, 7, 3, 8, 10), 0, 0, 0,
            Element.FIRE, Element.ICE,
            new Weapon("Wolf Fangs", Element.PIERCE, 0, 0,
                    new Move("Bite", 12, 0.95, Element.PIERCE),
                    new Move("Pounce", 18, 0.70, Element.SLASH)),
            18, 28);

    static final EnemyType WOLVERINE = new EnemyType("Wolverine", 115, statBlock(10, 13, 8, 3, 7, 9), 1, 0, 2,
            Element.LIGHTNING, null,
            new Weapon("Wolverine Claws", Element.SLASH, 0, 0,
                    new Move("Claw Rake", 13, 0.90, Element.SLASH),
                    new Move("Savage Bite", 19, 0.70, Element.PIERCE)),
            18, 28);

    static final EnemyType GIANT_ELK = new EnemyType("Giant Elk", 170, statBlock(11, 10, 9, 3, 6, 8), 4, 0, 0,
            Element.SLASH, Element.ICE,
            new Weapon("Great Antlers", Element.PIERCE, 0, 0,
                    new Move("Gore", 17, 0.80, Element.PIERCE),
                    new Move("Trample", 24, 0.65, Element.BLUNT)),
            26, 38);

    static final EnemyType BEAR = new EnemyType("Bear", 200, statBlock(13, 7, 11, 3, 6, 7), 6, 1, 1,
            Element.PIERCE, Element.ICE,
            new Weapon("Bear Claws", Element.SLASH, 0, 0,
                    new Move("Maul", 18, 0.85, Element.SLASH),
                    new Move("Bear Hug", 26, 0.60, Element.BLUNT)),
            34, 48);

    static final EnemyType MAMMOTH = new EnemyType("Mammoth", 250, statBlock(13, 3, 12, 2, 4, 6), 8, 3, 0,
            Element.FIRE, Element.BLUNT,
            new Weapon("Mammoth Tusks", Element.PIERCE, 0, 0,
                    new Move("Tusk Thrust", 20, 0.75, Element.PIERCE),
                    new Move("Stomp", 28, 0.55, Element.BLUNT)),
            45, 65);

    // Pack wolves have 40% less HP than the Dire Wolf Alpha that leads them.
    static final EnemyType PACK_WOLF = new EnemyType("Dire Wolf", DIRE_WOLF_ALPHA_HP * 6 / 10, statBlock(9, 14, 7, 3, 8, 10), 0, 0, 0,
            Element.FIRE, Element.ICE,
            new Weapon("Wolf Claws", Element.SLASH, 0, 0,
                    new Move("Claw Slash", 12, 0.95, Element.SLASH, Status.BLEED, 0.25),
                    new Move("Rending Pounce", 18, 0.75, Element.SLASH, Status.BLEED, 0.40)),
            12, 18);

    static final EnemyType DIRE_WOLF_ALPHA = new EnemyType("Dire Wolf Alpha", DIRE_WOLF_ALPHA_HP, statBlock(10, 15, 7, 3, 8, 10), 1, 0, 1,
            Element.FIRE, Element.ICE,
            new Weapon("Alpha Claws", Element.SLASH, 0, 0,
                    new Move("Claw Slash", 13, 0.95, Element.SLASH, Status.BLEED, 0.30),
                    new Move("Rending Pounce", 19, 0.75, Element.SLASH, Status.BLEED, 0.45)),
            32, 48, 1, 1, PACK_WOLF, 1, 2);

    static final EnemyType[] SNOWY_PEAKS = { DIRE_WOLF, WOLVERINE, GIANT_ELK, BEAR, MAMMOTH, DIRE_WOLF_ALPHA };

    // ---------------- Frostfang Phantom (boss) moves ----------------
    static final Move PHANTOM_BITE = new Move("Bite", 17, 0.85, Element.PIERCE, false, 0, false,
            MoveTag.BITE, 1, 1, Status.BLEED, 0.10, Status.FROSTBITE, 0.10);
    static final Move PHANTOM_SWIPES = new Move("Raging Swipes", 5, 0.80, Element.SLASH, false, 0, false,
            MoveTag.CLAWS, 2, 6, Status.BLEED, 0.05);
    static final Move PHANTOM_POUNCE = new Move("Pounce", 22, 0.80, Element.BLUNT, false, 0, false,
            MoveTag.POUNCE, 1, 1, Status.BLEED, 0.10, Status.FROSTBITE, 0.10);
    static final Move FOG_CLOAK = new Move("Fog Cloak", 0, 1.0, null, false, 0, false);
    static final Move LOCK_ON = new Move("Lock On", 0, 1.0, null, false, 0, false);

    // ---------------- Steelscale Wyvern / Calamitatum Rex moves ----------------
    static final Move DRAGON_BITE = new Move("Bite", 17, 0.85, Element.PIERCE, false, 0, false,
            MoveTag.BITE, 1, 1, Status.BLEED, 0.10);
    static final Move DRAGON_DIVEBOMB = new Move("Divebomb", 30, 0.70, Element.BLUNT, false, 0, false,
            MoveTag.DIVE, 1, 1, null, 0.0);
    static final Move DRAGON_TAIL_SWIPE = new Move("Tail Swipe", 15, 0.90, Element.SLASH, false, 0, false,
            MoveTag.TAIL, 1, 1, null, 0.0);
    static final Move DRAGON_TAIL_PIERCE = new Move("Tail Pierce", 18, 0.77, Element.PIERCE, false, 0, false,
            MoveTag.TAIL_PIERCE, 1, 1, Status.BLEED, 0.20);
    static final Move DRAGON_RAILGUN = new Move("Railgun", 25, 0.75, Element.LIGHTNING, false, 0, false,
            MoveTag.RAILGUN, 1, 1, Status.STATIC, 0.20);
    static final Move WYVERN_SWIPES = new Move("Raging Swipes", 5, 0.80, Element.SLASH, false, 0, false,
            MoveTag.CLAWS, 2, 6, Status.BLEED, 0.05);
    static final Move WYVERN_SLAM = new Move("Slam", 17, 0.90, Element.BLUNT, false, 0, false,
            MoveTag.SLAM, 1, 1, null, 0.0);
    static final Move THUNDER_CLOAK = new Move("Thunder Cloak", 0, 1.0, null, false, 0, false);
    static final Move REX_SWIPES = new Move("Draconic Swipes", 8, 0.80, Element.SLASH, false, 0, false,
            MoveTag.CLAWS, 2, 6, null, 0.0);
    static final Move REX_FLAMESHOT = new Move("Flameshot", 25, 0.75, Element.FIRE, false, 0, false,
            MoveTag.BREATH, 1, 1, Status.BURN, 0.20);
    static final Move REX_FROSTWIND = new Move("Frostwind Breath", 25, 0.75, Element.ICE, false, 0, false,
            MoveTag.BREATH, 1, 1, Status.FROSTBITE, 0.20);

    // ---------------- Stormspire enemies ----------------
    static final EnemyType STORMFEATHER_EAGLE = new EnemyType("Stormfeather Eagle", 115, statBlock(9, 18, 5, 3, 7, 10), 0, 0, 1,
            Element.ICE, Element.LIGHTNING,
            new Weapon("Storm Talons", Element.SLASH, 0, 0,
                    new Move("Talon Rake", 13, 0.90, Element.SLASH, Status.BLEED, 0.20),
                    new Move("Static Dive", 17, 0.75, Element.LIGHTNING, Status.STATIC, 0.20)),
            22, 34, 1, 2, null, 0, 0);

    static final EnemyType STONE_GOLEM = new EnemyType("Stone Golem", 250, statBlock(11, 3, 9, 2, 4, 8), 6, 2, 2,
            Element.ICE, Element.SLASH,
            new Weapon("Stone Fists", Element.BLUNT, 0, 0,
                    new Move("Slam", 14, 0.85, Element.BLUNT),
                    new Move("Crush", 22, 0.60, Element.BLUNT)),
            45, 65);

    static final EnemyType STORMHIDE_ELK = new EnemyType("Stormhide Elk", 220, statBlock(13, 11, 10, 3, 6, 8), 4, 0, 0,
            Element.BLUNT, Element.LIGHTNING,
            new Weapon("Stormhide Antlers", Element.PIERCE, 0, 0,
                    new Move("Gore", 18, 0.80, Element.PIERCE),
                    new Move("Thunder Trample", 25, 0.65, Element.BLUNT, Status.STATIC, 0.15)),
            34, 50);

    static final EnemyType[] STORMSPIRE = { STORMFEATHER_EAGLE, STONE_GOLEM, STORMHIDE_ELK };

    // ==================================================================
    
    static class Locale {
        final String name;
        final String intro;        // shown when the player arrives (null for the starting locale)
        final String[] quiet;      // flavor text for "nothing happens"
        final EnemyType[] enemies;
        final Item welcomeItem;    // optional gift on arrival (null for none)
        final int welcomeQty;
        final boolean bossOnly;    // no regular enemies: the boss is waiting for you

        Locale(String name, String intro, String[] quiet, EnemyType[] enemies, Item welcomeItem, int welcomeQty,
               boolean bossOnly) {
            this.name = name;
            this.intro = intro;
            this.quiet = quiet;
            this.enemies = enemies;
            this.welcomeItem = welcomeItem;
            this.welcomeQty = welcomeQty;
            this.bossOnly = bossOnly;
        }
    }

    static final Locale[] LOCALES = {
        new Locale("Forest", null,
                new String[] {
                    "You wander through the trees, but nothing happens.",
                    "The path is quiet. Only the wind keeps you company.",
                    "You find nothing but old footprints."
                },
                FOREST, null, 0, false),
        new Locale("Snowy Peaks",
                "The trees thin out and the air turns bitter. Before you rise the SNOWY PEAKS.",
                new String[] {
                    "Snow crunches under your boots, but nothing happens.",
                    "A cold wind howls across the ridge. You see nothing.",
                    "You find only half-buried tracks in the snow."
                },
                SNOWY_PEAKS, ITEMS[5], 2, false), // Warm Drinks cure frostbite and static
        new Locale("Stormspire",
                "Black clouds crown a jagged tower of rock, and lightning dances along its edge. Before you rises the STORMSPIRE.",
                new String[] {
                    "Thunder rolls overhead, but nothing happens.",
                    "The air tingles with static. You find nothing.",
                    "Rain lashes the cliffs. You find only wet stone."
                },
                STORMSPIRE, ITEMS[5], 2, false),
        new Locale("The Calamitous Alatar",
                "Beyond the Stormspire the world tears open into the CALAMITOUS ALATAR - a battlefield where fire, frost and lightning take turns ruling the sky. Something ancient is waiting...",
                new String[] { "The arena is silent. Something is watching you." },
                new EnemyType[0], null, 0, true)   // boss only: unlocked once the other locales are cleared
    };

    /** Creates the boss of the given locale. Add a case here for every new locale. */
    static Boss createBoss(int localeIndex) {
        switch (localeIndex) {
            case 0: return new Cockatrice();
            case 1: return new FrostfangPhantom();
            case 2: return new SteelscaleWyvern();
            case 3: return new CalamitatumRex();
            default: throw new IllegalArgumentException("Unknown locale index: " + localeIndex);
        }
    }

    // ==================================================================
    // Inventory (arrays only): stackable consumables, owned weapons, owned armor
    // ==================================================================
    static class Inventory {
        static final int ITEM_SLOTS = ITEMS.length;
        static final int WEAPON_SLOTS = WEAPONS.length;
        static final int ARMOR_SLOTS = ARMORS.length;

        final Item[] items = new Item[ITEM_SLOTS];
        final int[] counts = new int[ITEM_SLOTS];
        final Weapon[] weapons = new Weapon[WEAPON_SLOTS];
        final Armor[] armors = new Armor[ARMOR_SLOTS];
        int itemCount = 0;
        int weaponCount = 0;
        int armorCount = 0;

        boolean addItem(Item item, int qty) {
            for (int i = 0; i < itemCount; i++) {
                if (items[i] == item) {
                    counts[i] += qty;
                    return true;
                }
            }
            if (itemCount == ITEM_SLOTS) return false;
            items[itemCount] = item;
            counts[itemCount++] = qty;
            return true;
        }

        /** Uses up one of the item at this slot, removing the slot when it hits zero. */
        void removeOne(int index) {
            if (--counts[index] > 0) return;
            for (int i = index; i < itemCount - 1; i++) {
                items[i] = items[i + 1];
                counts[i] = counts[i + 1];
            }
            itemCount--;
            items[itemCount] = null;
            counts[itemCount] = 0;
        }

        boolean hasWeapon(Weapon w) {
            for (int i = 0; i < weaponCount; i++) {
                if (weapons[i] == w) return true;
            }
            return false;
        }

        boolean addWeapon(Weapon w) {
            if (hasWeapon(w) || weaponCount == WEAPON_SLOTS) return false;
            weapons[weaponCount++] = w;
            return true;
        }

        boolean hasArmor(Armor a) {
            for (int i = 0; i < armorCount; i++) {
                if (armors[i] == a) return true;
            }
            return false;
        }

        boolean addArmor(Armor a) {
            if (hasArmor(a) || armorCount == ARMOR_SLOTS) return false;
            armors[armorCount++] = a;
            return true;
        }
    }

    // ==================================================================
    // Fighters
    // ==================================================================
    static class Fighter {
        final String name;
        final int[] scores;            // base stats, indexed like STAT_NAMES
        int maxHp;
        int hp;
        Weapon weapon;
        Armor armor;
        Position position = Position.FRONT;
        boolean guarding = false;
        boolean dodging = false;       // Dodge: evasion x3 until the next turn
        boolean charged = false;
        boolean stunned = false;       // loses its next turn
        boolean infused = false;       // current attack coated in flaming gel (boss)

        // Damage over time
        boolean bleeding = false;
        int bleedDamage = BLEED_START;
        boolean poisoned = false;
        boolean burning = false;
        boolean frostbitten = false;
        boolean shocked = false;       // static
        boolean shockTrap = false;     // a Shock Trap has been set on this fighter
        int flashTurns = 0;            // Flashbomb: accuracy -30% for this many of its turns
        int smokeTurns = 0;            // Smoke Bomb: every attack on this fighter misses

        // Innate values (used by enemies; players get theirs from gear)
        int defenseBase = 0;
        int damageReductionBase = 0;
        int armorPenBase = 0;
        double evasionBase = 0.0;
        Element weakness = null;
        Element resistance = null;
        boolean weaknessKnown = false;     // has the player discovered this affinity?
        boolean resistanceKnown = false;
        int goldReward = 0;

        Fighter(String name, int maxHp, int[] scores, Weapon weapon) {
            this.name = name;
            this.maxHp = maxHp;
            this.hp = maxHp;
            this.scores = scores;
            this.weapon = weapon;
        }

        /** TOTAL stat = base score + gear bonuses (frostbite lowers Dexterity by 10%). */
        int totalStat(int stat) {
            int total = scores[stat] + (armor == null ? 0 : armor.bonus[stat]);
            if (stat == DEX && frostbitten) total = (int) Math.round(total * (1.0 - FROSTBITE_DEX_CUT));
            return total;
        }

        int mod(int stat) {
            return Math.floorDiv(totalStat(stat) - 10, 2);
        }

        double dexBonus() {
            return mod(DEX) * 0.02; // hit and retreat chance
        }

        double critRate() {
            return BASE_CRIT_RATE + Math.max(0, mod(LCK)) * 0.01;
        }

        /** Health regen: 1 HP per turn for each point of Constitution modifier (0 if the modifier is 0 or negative). */
        int regen() {
            return Math.max(0, mod(CON));
        }

        /** Burning cuts defense by 15% (25% if the target is weak to fire). */
        int defense() {
            int base = defenseBase + (armor == null ? 0 : armor.defense);
            if (!burning) return base;
            double cut = weakness == Element.FIRE ? 0.25 : 0.15;
            return (int) Math.round(base * (1.0 - cut));
        }

        int damageReduction() {
            return Math.min(MAX_DAMAGE_REDUCTION, damageReductionBase + (armor == null ? 0 : armor.damageReduction));
        }

        int armorPen() {
            return armorPenBase + (weapon == null ? 0 : weapon.armorPen);
        }

        Element activeResistance() {
            if (resistance != null) return resistance;
            return armor == null ? null : armor.resist;
        }

        // ---- Evasion: base x3 while dodging, plus any temporary bonus ----
        double baseEvasion() {
            return evasionBase;
        }

        double extraEvasion() {
            return 0.0;
        }

        double evasion() {
            double e = baseEvasion() * (dodging ? DODGE_EVASION_MULT : 1.0) + extraEvasion();
            return Math.max(0.0, Math.min(0.90, e));
        }

        // ---- Hooks the bosses override ----
        double damageTakenMult() {
            return 1.0;
        }

        double fireMult() {
            return 1.0;
        }

        /** The element a move actually has right now (bosses can change it). */
        Element elementOf(Move move) {
            return move.element;
        }

        double outgoingMult(Move move, int hit) {
            return elementOf(move) == Element.FIRE ? fireMult() : 1.0;
        }

        /** Called when a melee attack lands on this fighter. */
        void onMeleeHitBy(Fighter attacker, Move move) {
        }

        double accuracyOf(Move move, Fighter target) {
            return move.hitChance;
        }

        /** Chance that this move inflicts the given status. */
        double statusChanceOf(Move move, Status status) {
            if (move.status == status) return move.statusChance;
            if (move.status2 == status) return move.statusChance2;
            return 0.0;
        }

        void clearStatuses() {
            bleeding = false;
            bleedDamage = BLEED_START;
            poisoned = false;
            burning = false;
            frostbitten = false;
            shocked = false;
            shockTrap = false;
            flashTurns = 0;
            smokeTurns = 0;
            stunned = false;
            infused = false;
            charged = false;
            guarding = false;
            dodging = false;
        }

        boolean isAlive() {
            return hp > 0;
        }

        void heal(int amount) {
            hp = Math.min(maxHp, hp + amount);
        }

        void takeDamage(int amount) {
            hp = Math.max(0, hp - amount);
        }
    }

    static class Player extends Fighter {
        final Inventory inventory;
        int gold = STARTING_GOLD;
        int encountersCleared = 0;
        int bossesDefeated = 0;
        int victories = 0;                               // victorious battles; every victory is a level up
        double rollRating = 1.0;                         // how good the starting stat roll was (1.0 = average)
        int locale = 0;                                  // index into LOCALES
        final boolean[] cleared = new boolean[LOCALES.length];
        int skillPoints = 0;                             // 1 per victorious battle, spent on stats
        boolean gameComplete = false;
        int smokeCooldown = 0;                           // turns until a Smoke Bomb can be used again
        int flashTimer = 0;                              // turns left on the Flashbomb effect
        int flashCooldown = 0;                           // turns until a Flashbomb can be used again
        final boolean[] knownSkills = new boolean[SKILLS.length];
        final int[] skillUses = new int[SKILLS.length];

        Player(String name, int[] scores, Inventory inventory) {
            super(name, 100, scores, null);
            this.inventory = inventory;
            recalcMaxHp();
            hp = maxHp;
        }

        /** Level 1 at the start; +1 for every victorious battle. */
        int level() {
            return 1 + victories;
        }

        /** Constitution -> HP bonus (none if the modifier is 0 or negative). Uses base Constitution. */
        void recalcMaxHp() {
            maxHp = 100 + Math.max(0, Math.floorDiv(scores[CON] - 10, 2)) * 5;
            if (hp > maxHp) hp = maxHp;
        }

        /** Players dodge based on Dexterity: 1% evasion per point of TOTAL Dexterity. */
        @Override
        double baseEvasion() {
            return 0.01 * totalStat(DEX);
        }

        /** Intelligence makes items stronger. */
        int potency() {
            return Math.max(0, mod(INT)) * 2;
        }

        /** Base 25% + 5% per point of TOTAL Intelligence. */
        double learnChance() {
            return Math.min(1.0, 0.25 + 0.05 * totalStat(INT));
        }

        void resetSkillUses() {
            for (int i = 0; i < SKILLS.length; i++) {
                skillUses[i] = SKILLS[i].uses;
            }
        }
    }

    // ==================================================================
    // Bosses (one per locale). Damage to a body part also drains the boss's main HP.
    // ==================================================================
    static class BodyPart {
        final String name;
        int maxHp;
        int hp;
        final Element[] weakTo;
        boolean known = false;

        BodyPart(String name, int maxHp, Element... weakTo) {
            this.name = name;
            this.maxHp = maxHp;
            this.hp = maxHp;
            this.weakTo = weakTo;
        }

        boolean broken() {
            return hp <= 0;
        }

        /** Enemy scaling: resizes this part's health. */
        void scale(double k) {
            maxHp = Math.max(1, (int) Math.round(maxHp * k));
            hp = maxHp;
        }

        boolean isWeakTo(Element e) {
            for (int i = 0; i < weakTo.length; i++) {
                if (weakTo[i] == e) return true;
            }
            return false;
        }

        String weakText() {
            if (!known) return "???";
            String text = "";
            for (int i = 0; i < weakTo.length; i++) {
                text += (i > 0 ? ", " : "") + weakTo[i];
            }
            return text;
        }
    }

    abstract static class Boss extends Fighter {
        final BodyPart[] parts;
        boolean enraged = false;
        int turn = 0; // how many turns this boss has taken

        Boss(String name, int maxHp, int[] stats, Weapon weapon, BodyPart[] parts) {
            super(name, maxHp, stats, weapon);
            this.parts = parts;
            weaknessKnown = true;   // its weak points are its body parts
            resistanceKnown = true;
        }

        abstract String introText();

        abstract String defeatText();

        /** The boss's actions for one turn (damage over time and stun are already handled). */
        abstract void takeTurn(Fighter target);

        /** Called for every hit that lands on one of its body parts. */
        void onPartHit(BodyPart part, Move move, int damage) {
            boolean wasBroken = part.broken();
            part.hp = Math.max(0, part.hp - damage);
            System.out.printf("  The %s takes %d damage. (%d/%d HP)%n", part.name, damage, part.hp, part.maxHp);
            if (!wasBroken && part.broken()) {
                System.out.println("  The " + name + "'s " + part.name.toUpperCase() + " IS DESTROYED!");
                onPartDestroyed(part);
            } else if (!part.broken()) {
                onPartStruck(part, move);
            }
        }

        /** Hook: effects of a part being destroyed (messages; the effects themselves live in the overrides). */
        void onPartDestroyed(BodyPart part) {
        }

        /** Hook: effects of hitting a part that is still standing (stuns, etc.). */
        void onPartStruck(BodyPart part, Move move) {
        }

        /** True if the body part with this name exists and is destroyed. */
        boolean partBroken(String partName) {
            for (BodyPart bp : parts) {
                if (bp.name.equals(partName)) return bp.broken();
            }
            return false;
        }

        /** Returns true on the turn the boss drops to 50% HP. */
        boolean checkEnrage() {
            if (!enraged && hp * 2 <= maxHp) {
                enraged = true;
                return true;
            }
            return false;
        }
    }

    // ---------------- Forest boss ----------------
    static class Cockatrice extends Boss {
        static final int HEAD = 0, THROAT = 1, RIGHT_LEG = 2, LEFT_LEG = 3, TAIL = 4;
        double iceWeaken = 0.0; // fire damage lost to ice hits on the throat

        Cockatrice() {
            super("Cockatrice", 400, statBlock(4, 8, 5, 2, 6, 10),
                    new Weapon("Cockatrice Body", Element.PIERCE, 0, 0, PIERCE_STRIKE),
                    new BodyPart[] {
                        new BodyPart("Head", 150, Element.BLUNT),
                        new BodyPart("Throat", 100, Element.BLUNT, Element.SLASH, Element.ICE),
                        new BodyPart("Right Leg", 50, Element.BLUNT),
                        new BodyPart("Left Leg", 50, Element.BLUNT),
                        new BodyPart("Tail", 100, Element.SLASH)
                    });
            defenseBase = 1;
            damageReductionBase = 0;
            armorPenBase = 1;
            evasionBase = 0.08;
            goldReward = 250;
        }

        @Override
        String introText() {
            return "A deafening screech shakes the trees...\nThe COCKATRICE bursts out of the undergrowth!";
        }

        @Override
        String defeatText() {
            return "The Cockatrice has been slain! The forest falls silent.";
        }

        /** Evasion drops 20% when enraged (it attacks more recklessly). */
        @Override
        double baseEvasion() {
            return enraged ? evasionBase * 0.8 : evasionBase;
        }

        /**
         * Fire output: weakened 30% once the throat is at 50% health, disabled when the throat is broken.
         * Ice hits on the throat weaken it further, down to 50%.
         */
        @Override
        double fireMult() {
            BodyPart throat = parts[THROAT];
            if (throat.broken()) return 0.0;
            double m = throat.hp * 2 <= throat.maxHp ? 0.70 : 1.0;
            return Math.max(0.50, m - iceWeaken);
        }

        @Override
        double outgoingMult(Move move, int hit) {
            double m = super.outgoingMult(move, hit);
            if (move.tag == MoveTag.BEAK && parts[HEAD].broken()) m *= 0.60;
            if (move.tag == MoveTag.TAIL && parts[TAIL].broken()) m *= 0.40;
            if (move.tag == MoveTag.CLAWS) {
                boolean rightClaw = hit % 2 == 1; // odd strikes use the right claw, even strikes the left
                if (rightClaw && parts[RIGHT_LEG].broken()) m *= 0.30;
                if (!rightClaw && parts[LEFT_LEG].broken()) m *= 0.30;
            }
            return m;
        }

        @Override
        double accuracyOf(Move move, Fighter target) {
            if (move.tag == MoveTag.TAIL && target.position == Position.BACK) return 0.40;
            return move.hitChance;
        }

        @Override
        double statusChanceOf(Move move, Status status) {
            if (move.tag == MoveTag.TAIL && parts[TAIL].broken() && status == Status.POISON) return 0.0; // no more poison
            return super.statusChanceOf(move, status);
        }

        @Override
        void onPartDestroyed(BodyPart part) {
            if (part == parts[HEAD]) System.out.println("  Its beak attacks will deal 40% less damage.");
            if (part == parts[THROAT]) System.out.println("  It can no longer use fire attacks!");
            if (part == parts[TAIL]) System.out.println("  Its tail swipes deal 60% less damage and can no longer poison.");
            if (part == parts[RIGHT_LEG]) System.out.println("  Its right claw strikes (odd swipes) deal 70% less damage.");
            if (part == parts[LEFT_LEG]) System.out.println("  Its left claw strikes (even swipes) deal 70% less damage.");
            if (parts[RIGHT_LEG].broken() && parts[LEFT_LEG].broken()) {
                System.out.println("  With both legs destroyed, it can no longer pounce!");
            }
        }

        @Override
        void onPartStruck(BodyPart part, Move move) {
            // Head: blunt hits have a 20% chance to stun
            if (part == parts[HEAD] && move.element == Element.BLUNT && !stunned && RNG.nextInt(100) < 20) {
                stunned = true;
                System.out.println("  The blow rattles the Cockatrice's skull - it is STUNNED!");
            }
            // Throat: ice hits further weaken its fire (down to 50%)
            if (part == parts[THROAT] && move.element == Element.ICE) {
                iceWeaken += 0.10;
                System.out.printf("  The cold weakens its fire. (fire output now %d%%)%n", (int) Math.round(fireMult() * 100));
            }
        }

        Move pickMove() {
            Move[] pool = new Move[7];
            int n = 0;
            pool[n++] = PIERCE_STRIKE;
            pool[n++] = RAGING_SWIPES;
            pool[n++] = TAIL_SWIPE;
            if (!parts[THROAT].broken()) {
                pool[n++] = FLAMESHOT;
                pool[n++] = FLAMETHROWER;
            }
            if (!(parts[RIGHT_LEG].broken() && parts[LEFT_LEG].broken())) pool[n++] = POUNCE;
            if (!charged) pool[n++] = CHARGE;
            return pool[RNG.nextInt(n)];
        }

        @Override
        void takeTurn(Fighter target) {
            turn++;
            if (checkEnrage()) {
                System.out.println("The Cockatrice becomes ENRAGED! It attacks recklessly (less evasion),");
                System.out.println("and its attacks may be infused with flaming gel!");
            }
            Move move = pickMove();
            infused = enraged && !move.charge && RNG.nextInt(100) < 35;
            if (infused) System.out.println("Flaming gel drips from the Cockatrice's attack!");
            performAttack(this, target, move, null);
            infused = false;
        }
    }

    // ---------------- Snowy Peaks boss ----------------
    static class FrostfangPhantom extends Boss {
        static final int HEAD = 0, HIND_LEGS = 1, RIGHT_FORELEG = 2, LEFT_FORELEG = 3, TAIL = 4;
        int fogTurns = 0;      // Fog Cloak: +20% evasion
        int lockOnTurns = 0;   // Lock On: +10% accuracy, +20% crit rate

        FrostfangPhantom() {
            super("Frostfang Phantom", 400, statBlock(6, 18, 5, 3, 6, 10),   // high Dex
                    new Weapon("Phantom Fangs", Element.PIERCE, 0, 0, PHANTOM_BITE),
                    new BodyPart[] {
                        new BodyPart("Head", 110, Element.BLUNT),
                        new BodyPart("Hind Legs", 100, Element.BLUNT, Element.SLASH),
                        new BodyPart("Right Foreleg", 60, Element.BLUNT),
                        new BodyPart("Left Foreleg", 60, Element.BLUNT),
                        new BodyPart("Tail", 70, Element.SLASH)
                    });
            defenseBase = 0;        // low defense
            damageReductionBase = 0;
            armorPenBase = 2;
            evasionBase = 0.15;
            goldReward = 350;
        }

        @Override
        String introText() {
            return "The temperature plummets and the snow swirls...\nThe FROSTFANG PHANTOM fades into view and howls!";
        }

        @Override
        String defeatText() {
            return "The Frostfang Phantom dissolves into drifting snow. The peaks fall quiet.";
        }

        /** A severed tail costs it 3 points of Dexterity. */
        @Override
        int totalStat(int stat) {
            int v = super.totalStat(stat);
            if (stat == DEX && parts[TAIL].broken()) v = Math.max(0, v - 3);
            return v;
        }

        /** Fog Cloak +20%; enraged +10% (only +5% once the hind legs are destroyed). */
        @Override
        double extraEvasion() {
            double e = fogTurns > 0 ? 0.20 : 0.0;
            if (enraged) e += parts[HIND_LEGS].broken() ? 0.05 : 0.10;
            return e;
        }

        /** Enraged: it takes 10% more damage. */
        @Override
        double damageTakenMult() {
            return enraged ? 1.10 : 1.0;
        }

        /** Lock On: +20% crit rate. */
        @Override
        double critRate() {
            return super.critRate() + (lockOnTurns > 0 ? 0.20 : 0.0);
        }

        /** Lock On: +10% accuracy. Pounce loses 20% accuracy at 50% hind leg HP and another 40% when they break. */
        @Override
        double accuracyOf(Move move, Fighter target) {
            double acc = move.hitChance;
            if (lockOnTurns > 0) acc += 0.10;
            if (move.tag == MoveTag.POUNCE) {
                BodyPart legs = parts[HIND_LEGS];
                if (legs.hp * 2 <= legs.maxHp) acc -= 0.20;
                if (legs.broken()) acc -= 0.40;
            }
            return Math.max(0.05, acc);
        }

        @Override
        double outgoingMult(Move move, int hit) {
            double m = super.outgoingMult(move, hit);
            if (enraged) m *= 1.10;
            if (move.tag == MoveTag.BITE && parts[HEAD].broken()) m *= 0.60;
            if (move.tag == MoveTag.CLAWS) {
                boolean rightClaw = hit % 2 == 1; // odd strikes use the right foreleg, even strikes the left
                if (rightClaw && parts[RIGHT_FORELEG].broken()) m *= 0.30;
                if (!rightClaw && parts[LEFT_FORELEG].broken()) m *= 0.30;
            }
            return m;
        }

        /** Enraged: +20% chance to bleed on every attack. */
        @Override
        double statusChanceOf(Move move, Status status) {
            double c = super.statusChanceOf(move, status);
            if (enraged && status == Status.BLEED && move.power > 0) c += 0.20;
            return c;
        }

        @Override
        void onPartDestroyed(BodyPart part) {
            if (part == parts[HEAD]) System.out.println("  Its bite attacks will deal 40% less damage.");
            if (part == parts[HIND_LEGS]) System.out.println("  It can no longer attack twice, and its pounce is far less accurate!");
            if (part == parts[RIGHT_FORELEG]) System.out.println("  Its right claw strikes (odd swipes) deal 70% less damage.");
            if (part == parts[LEFT_FORELEG]) System.out.println("  Its left claw strikes (even swipes) deal 70% less damage.");
            if (part == parts[TAIL]) System.out.println("  It loses its balance - its Dexterity drops by 3!");
        }

        @Override
        void onPartStruck(BodyPart part, Move move) {
            // Head: blunt hits have a 20% chance to stun
            if (part == parts[HEAD] && move.element == Element.BLUNT && !stunned && RNG.nextInt(100) < 20) {
                stunned = true;
                System.out.println("  The blow rattles the Phantom's skull - it is STUNNED!");
            }
        }

        Move pickAttack() {
            Move[] attacks = { PHANTOM_BITE, PHANTOM_SWIPES, PHANTOM_POUNCE };
            return attacks[RNG.nextInt(attacks.length)];
        }

        Move pickMove() {
            Move[] pool = new Move[6];
            int n = 0;
            pool[n++] = PHANTOM_BITE;
            pool[n++] = PHANTOM_SWIPES;
            pool[n++] = PHANTOM_POUNCE;
            if (fogTurns == 0) pool[n++] = FOG_CLOAK;
            if (lockOnTurns == 0) pool[n++] = LOCK_ON;
            if (!charged) pool[n++] = CHARGE;
            return pool[RNG.nextInt(n)];
        }

        @Override
        void takeTurn(Fighter target) {
            turn++;
            if (checkEnrage()) {
                System.out.println("The Frostfang Phantom becomes ENRAGED! It fights recklessly (takes 10% more damage),");
                System.out.println("but hits harder, bleeds its prey, and becomes harder to pin down!");
            }

            boolean castFog = false;
            boolean castLockOn = false;

            // Passive: on even turns, 80% chance to attack twice while the hind legs are intact.
            if (turn % 2 == 0 && !parts[HIND_LEGS].broken() && RNG.nextInt(100) < 80) {
                System.out.println("The Frostfang Phantom blurs forward - it attacks twice!");
                for (int i = 0; i < 2 && target.isAlive(); i++) {
                    performAttack(this, target, pickAttack(), null);
                }
            } else {
                Move move = pickMove();
                if (move == FOG_CLOAK) {
                    fogTurns = 3;
                    castFog = true;
                    System.out.println("The Frostfang Phantom wraps itself in swirling fog! (evasion +20% for 3 turns)");
                } else if (move == LOCK_ON) {
                    lockOnTurns = 3;
                    castLockOn = true;
                    System.out.println("The Frostfang Phantom locks its glowing eyes on you! (accuracy +10%, crit rate +20% for 3 turns)");
                } else {
                    performAttack(this, target, move, null);
                }
            }

            // Buffs last 3 turns; the turn they are cast does not use one up.
            if (!castFog && fogTurns > 0) fogTurns--;
            if (!castLockOn && lockOnTurns > 0) lockOnTurns--;
        }
    }

    // ---------------- Shared behavior of the dragon-type bosses ----------------
    static boolean isPhysical(Element e) {
        return e == Element.SLASH || e == Element.PIERCE || e == Element.BLUNT;
    }

    abstract static class DragonBoss extends Boss {
        boolean electricBoost = false;   // passive: the next attack deals +10%

        DragonBoss(String name, int maxHp, int[] stats, Weapon weapon, BodyPart[] parts) {
            super(name, maxHp, stats, weapon, parts);
        }

        abstract String boostMessage();

        boolean spinesIntact() {
            return !partBroken("Spines");
        }

        /** A severed tail costs 3 points of Dexterity. */
        @Override
        int totalStat(int stat) {
            int v = super.totalStat(stat);
            if (stat == DEX && partBroken("Tail")) v = Math.max(0, v - 3);
            return v;
        }

        /** Enraged: it takes 10% more damage. */
        @Override
        double damageTakenMult() {
            return enraged ? 1.10 : 1.0;
        }

        @Override
        double accuracyOf(Move move, Fighter target) {
            if (move.tag == MoveTag.TAIL && target.position == Position.BACK) return 0.40;
            return move.hitChance;
        }

        @Override
        double outgoingMult(Move move, int hit) {
            double m = super.outgoingMult(move, hit);
            if (enraged) m *= 1.15;
            if (electricBoost) m *= 1.10;
            if (move.tag == MoveTag.BITE && partBroken("Head")) m *= 0.60;
            if (move.tag == MoveTag.TAIL && partBroken("Tail")) m *= 0.40;
            if (move.tag == MoveTag.TAIL_PIERCE && partBroken("Tail")) m *= 0.30;
            return m;
        }

        @Override
        double statusChanceOf(Move move, Status status) {
            if (move.tag == MoveTag.TAIL_PIERCE && partBroken("Tail") && status == Status.BLEED) return 0.0; // no more bleed
            return super.statusChanceOf(move, status);
        }

        @Override
        void onPartDestroyed(BodyPart part) {
            if (part.name.equals("Head")) System.out.println("  Its bite attacks will deal 40% less damage.");
            if (part.name.equals("Wings")) System.out.println("  It can no longer use Divebomb!");
            if (part.name.equals("Spines")) System.out.println("  It can no longer channel electricity!");
            if (part.name.equals("Tail")) {
                System.out.println("  It loses its balance - its Dexterity drops by 3!");
                System.out.println("  Tail swipes deal 60% less damage; tail pierces deal 70% less and no longer cause bleeding.");
            }
        }

        @Override
        void onPartStruck(BodyPart part, Move move) {
            // Head: blunt hits have a 20% chance to stun
            if (part.name.equals("Head") && move.element == Element.BLUNT && !stunned && RNG.nextInt(100) < 20) {
                stunned = true;
                System.out.println("  The blow rattles its skull - the " + name + " is STUNNED!");
            }
        }

        /** Passive: 10% chance after its turn to boost its next attack by 10%. */
        void rollPassive(boolean allowed) {
            if (allowed && !electricBoost && RNG.nextInt(100) < 10) {
                electricBoost = true;
                System.out.println(boostMessage());
            }
        }
    }

    // ---------------- Stormspire boss ----------------
    static class SteelscaleWyvern extends DragonBoss {
        int cloakTurns = 0;   // Thunder Cloak

        SteelscaleWyvern() {
            super("Steelscale Wyvern", 550, statBlock(7, 12, 9, 3, 6, 10),
                    new Weapon("Wyvern Body", Element.PIERCE, 0, 0, DRAGON_BITE),
                    new BodyPart[] {
                        new BodyPart("Head", 150, Element.BLUNT),
                        new BodyPart("Spines", 100, Element.BLUNT),
                        new BodyPart("Wings", 120, Element.SLASH, Element.PIERCE),
                        new BodyPart("Wing Claws", 80, Element.BLUNT),
                        new BodyPart("Tail", 100, Element.SLASH)
                    });
            defenseBase = 8;
            damageReductionBase = 3;
            armorPenBase = 2;
            evasionBase = 0.10;
            goldReward = 450;
        }

        @Override
        String introText() {
            return "Lightning splits the sky...\nThe STEELSCALE WYVERN dives out of the storm clouds!";
        }

        @Override
        String defeatText() {
            return "The Steelscale Wyvern crashes down and goes still. The storm begins to fade.";
        }

        @Override
        String boostMessage() {
            return "The charged electricity will boost the Wyvern's attack on the next turn!";
        }

        boolean cloakActive() {
            return cloakTurns > 0 && spinesIntact();
        }

        /** Thunder Cloak: its physical attacks gain a LIGHTNING affinity. */
        @Override
        Element elementOf(Move move) {
            if (cloakActive() && isPhysical(move.element)) return Element.LIGHTNING;
            return move.element;
        }

        @Override
        double outgoingMult(Move move, int hit) {
            double m = super.outgoingMult(move, hit);
            if (move.tag == MoveTag.SLAM && partBroken("Wing Claws")) m *= 0.70;
            if (move.tag == MoveTag.CLAWS && partBroken("Wing Claws")) m *= 0.50;
            if (cloakActive()) {
                if (isPhysical(move.element)) m *= 1.10;
                if (move.tag == MoveTag.RAILGUN) m *= 1.20;
            }
            return m;
        }

        /** Broken wing claws also reduce the bleed chance of its claws by 10%. */
        @Override
        double statusChanceOf(Move move, Status status) {
            double c = super.statusChanceOf(move, status);
            if (move.tag == MoveTag.CLAWS && status == Status.BLEED && partBroken("Wing Claws")) c = Math.max(0.0, c - 0.10);
            return c;
        }

        /** Thunder Cloak: melee hits on the Wyvern have a 10% chance to inflict static on the attacker. */
        @Override
        void onMeleeHitBy(Fighter attacker, Move move) {
            if (cloakActive() && RNG.nextInt(100) < 10) {
                System.out.println("  Static crackles off the Wyvern's scales!");
                applyStatus(attacker, Status.STATIC);
            }
        }

        @Override
        void onPartDestroyed(BodyPart part) {
            super.onPartDestroyed(part);
            if (part.name.equals("Wing Claws")) {
                System.out.println("  Its slam is 30% weaker, its claw swipes 50% weaker, and they bleed less.");
            }
            if (part.name.equals("Spines")) cloakTurns = 0; // the cloak collapses
        }

        Move pickMove() {
            Move[] pool = new Move[9];
            int n = 0;
            pool[n++] = DRAGON_BITE;
            pool[n++] = WYVERN_SWIPES;
            pool[n++] = WYVERN_SLAM;
            pool[n++] = DRAGON_TAIL_SWIPE;
            pool[n++] = DRAGON_TAIL_PIERCE;
            if (!partBroken("Wings")) pool[n++] = DRAGON_DIVEBOMB;
            if (spinesIntact()) {
                pool[n++] = DRAGON_RAILGUN;
                if (cloakTurns == 0) pool[n++] = THUNDER_CLOAK;
            }
            if (!charged) pool[n++] = CHARGE;
            return pool[RNG.nextInt(n)];
        }

        @Override
        void takeTurn(Fighter target) {
            turn++;
            if (checkEnrage()) {
                System.out.println("The Steelscale Wyvern becomes ENRAGED! It hits harder (+15%) but takes 10% more damage!");
            }

            boolean boosted = electricBoost;
            boolean castCloak = false;
            Move move = pickMove();
            if (move == THUNDER_CLOAK) {
                cloakTurns = 3;
                castCloak = true;
                say("The Wyvern wraps itself in crackling lightning - THUNDER CLOAK!",
                    "  (its physical attacks turn LIGHTNING and hit 10% harder, Railgun hits 20% harder,",
                    "   and melee hits on it may shock you - for 3 turns)");
            } else {
                performAttack(this, target, move, null);
            }

            if (boosted) electricBoost = false;
            if (!castCloak && cloakTurns > 0) cloakTurns--;
            rollPassive(spinesIntact());
        }
    }

    // ---------------- Calamitous Alatar boss (final) ----------------
    static String arenaName(Element e) {
        if (e == Element.FIRE) return "HEATWAVE";
        if (e == Element.ICE) return "BLIZZARD";
        if (e == Element.LIGHTNING) return "THUNDERSTORM";
        return "";
    }

    static Status statusFor(Element e) {
        if (e == Element.FIRE) return Status.BURN;
        if (e == Element.ICE) return Status.FROSTBITE;
        if (e == Element.LIGHTNING) return Status.STATIC;
        return null;
    }

    static class CalamitatumRex extends DragonBoss {
        int arenaLeft = 0;   // turns left in the current arena effect
        int restLeft = 3;    // turns until the next arena effect (the first one begins on turn 3)

        CalamitatumRex() {
            super("Calamitatum Rex", 650, statBlock(8, 12, 10, 8, 6, 10),
                    new Weapon("Rex Body", Element.PIERCE, 0, 0, DRAGON_BITE),
                    new BodyPart[] {
                        new BodyPart("Head", 150, Element.BLUNT),
                        new BodyPart("Spines", 100, Element.BLUNT),
                        new BodyPart("Throat", 100, Element.BLUNT, Element.SLASH, Element.PIERCE),
                        new BodyPart("Wings", 80, Element.SLASH, Element.PIERCE),
                        new BodyPart("Right Foreleg", 60, Element.BLUNT),
                        new BodyPart("Left Foreleg", 60, Element.BLUNT),
                        new BodyPart("Tail", 100, Element.SLASH)
                    });
            defenseBase = 6;
            damageReductionBase = 3;
            armorPenBase = 3;
            evasionBase = 0.10;
            goldReward = 0;
        }

        @Override
        String introText() {
            return "The ground shudders. Fire, frost and lightning churn in the sky above...\nThe CALAMITATUM REX rises to meet you!";
        }

        @Override
        String defeatText() {
            return "The Calamitatum Rex lets out a final roar and collapses. The sky clears.";
        }

        @Override
        String boostMessage() {
            return "The charged element will boost the Dragon's attack on the next turn!";
        }

        boolean throatWeakened() {
            BodyPart throat = parts[2];
            return throat.hp * 2 <= throat.maxHp;
        }

        /** Draconic Swipes and Tail Pierce take on the element of the current arena effect. */
        @Override
        Element elementOf(Move move) {
            if (arenaEffect != null && (move.tag == MoveTag.CLAWS || move.tag == MoveTag.TAIL_PIERCE)) return arenaEffect;
            return move.element;
        }

        @Override
        double outgoingMult(Move move, int hit) {
            double m = super.outgoingMult(move, hit);
            // fire is weakened by 30% once the spines are destroyed
            if (elementOf(move) == Element.FIRE && partBroken("Spines")) m *= 0.70;
            // railgun, flameshot and frostwind breath are weakened by 30% at 50% throat HP
            if ((move.tag == MoveTag.RAILGUN || move.tag == MoveTag.BREATH) && throatWeakened()) m *= 0.70;
            if (move.tag == MoveTag.CLAWS) {
                boolean rightClaw = hit % 2 == 1; // odd strikes use the right foreleg, even strikes the left
                if (rightClaw && partBroken("Right Foreleg")) m *= 0.30;
                if (!rightClaw && partBroken("Left Foreleg")) m *= 0.30;
            }
            return m;
        }

        @Override
        void onPartDestroyed(BodyPart part) {
            super.onPartDestroyed(part);
            if (part.name.equals("Head")) System.out.println("  The blizzard can no longer be summoned.");
            if (part.name.equals("Spines")) System.out.println("  The thunderstorm can no longer be summoned, and its fire is 30% weaker.");
            if (part.name.equals("Throat")) {
                System.out.println("  Railgun, Flameshot and Frostwind Breath are disabled, and the heatwave can no longer be summoned.");
            }
            if (part.name.equals("Right Foreleg")) System.out.println("  Its right claw strikes (odd swipes) deal 70% less damage.");
            if (part.name.equals("Left Foreleg")) System.out.println("  Its left claw strikes (even swipes) deal 70% less damage.");
        }

        /** Destroying a part disables one arena effect. */
        boolean arenaBlocked(Element e) {
            if (e == Element.ICE) return partBroken("Head");
            if (e == Element.LIGHTNING) return partBroken("Spines");
            if (e == Element.FIRE) return partBroken("Throat");
            return false;
        }

        void startArena() {
            Element[] all = { Element.FIRE, Element.ICE, Element.LIGHTNING };
            Element[] pool = new Element[3];
            int n = 0;
            for (int i = 0; i < all.length; i++) {
                if (!arenaBlocked(all[i])) pool[n++] = all[i];
            }
            if (n == 0) {
                restLeft = 1; // nothing left to summon
                return;
            }
            arenaEffect = pool[RNG.nextInt(n)];
            arenaLeft = enraged ? 4 : 3;
            System.out.println("The arena shifts! A " + arenaName(arenaEffect) + " takes hold - every " + arenaEffect
                    + " attack deals +20% damage and has +10% status chance, for everyone!");
        }

        /** Every 5 turns: 3 turns of an arena effect, then 2 turns of calm (4 and 1 when enraged). */
        void advanceArena() {
            if (arenaEffect != null) {
                arenaLeft--;
                if (arenaLeft <= 0 || arenaBlocked(arenaEffect)) {
                    System.out.println("The " + arenaName(arenaEffect) + " fades. The arena returns to normal.");
                    arenaEffect = null;
                    restLeft = enraged ? 1 : 2;
                }
            } else {
                restLeft--;
                if (restLeft <= 0) startArena();
            }
        }

        Move pickMove() {
            Move[] pool = new Move[9];
            int n = 0;
            pool[n++] = DRAGON_BITE;
            pool[n++] = REX_SWIPES;
            pool[n++] = DRAGON_TAIL_SWIPE;
            pool[n++] = DRAGON_TAIL_PIERCE;
            if (!partBroken("Wings")) pool[n++] = DRAGON_DIVEBOMB;
            if (!partBroken("Throat")) {
                if (spinesIntact()) pool[n++] = DRAGON_RAILGUN;
                pool[n++] = REX_FLAMESHOT;
                pool[n++] = REX_FROSTWIND;
            }
            if (!charged) pool[n++] = CHARGE;
            return pool[RNG.nextInt(n)];
        }

        @Override
        void takeTurn(Fighter target) {
            turn++;
            if (checkEnrage()) {
                System.out.println("The Calamitatum Rex becomes ENRAGED! It hits harder (+15%) but takes 10% more damage,");
                System.out.println("and its arena effects now last 4 turns with only 1 turn of calm!");
            }
            advanceArena();

            boolean boosted = electricBoost;
            performAttack(this, target, pickMove(), null);
            if (boosted) electricBoost = false;
            rollPassive(true);
        }
    }

    // ==================================================================

    enum Outcome { WIN, LOSE, FLED }

    // ==================================================================
    // Input helpers
    // ==================================================================
    static int readInt(int min, int max) {
        while (true) {
            System.out.print("> ");
            String line = SC.nextLine().trim();
            try {
                int value = Integer.parseInt(line);
                if (value >= min && value <= max) return value;
            } catch (NumberFormatException ignored) {
            }
            System.out.printf("Enter a number between %d and %d.%n", min, max);
        }
    }

    /** Prints each line. */
    static void say(String... lines) {
        for (String line : lines) System.out.println(line);
    }

    static void pause() {
        System.out.print("Press Enter to continue...");
        SC.nextLine();
    }

    // ==================================================================
    // Character creation and info screens
    // ==================================================================
    static Player newCharacter() {
        System.out.println(LINE);
        System.out.print("Enter Character Name: ");
        String name = SC.nextLine().trim();
        if (name.isEmpty()) name = "Hero";

        int[] scores = new int[STAT_NAMES.length];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = 100;   // 100 in every stat
        }

        Inventory inv = new Inventory();
        Player p = new Player(name, scores, inv);
        p.armor = ARMORS[0];                 // Leather Set
        p.rollRating = rollRating(scores);
        p.weapon = GG_EZ_WEAPON;
        p.locale = LOCALES.length - 1;                       // straight to the last locale
        for (int i = 0; i < p.locale; i++) p.cleared[i] = true;

        inv.addWeapon(GG_EZ_WEAPON);
        inv.addArmor(ARMORS[0]);
        inv.addItem(ITEMS[0], 2);  // 2 Potions
        inv.addItem(ITEMS[2], 1);  // 1 Bomb
        inv.addItem(ITEMS[3], 1);  // 1 Antidote
        inv.addItem(ITEMS[4], 1);  // 1 Water Flask

        showCharacter(p);
        System.out.println("Your only move is \"gg ez\" - it deals 999 damage.");
        return p;
    }

    static void showCharacter(Player p) {
        System.out.println(LINE);
        System.out.println("Character Name: " + p.name);
        System.out.println("Level: " + p.level() + "    Starting roll: " + rollLabel(p.rollRating) + String.format(" (%.2f)", p.rollRating));
        System.out.println("HP: " + p.hp + "/" + p.maxHp + "    Gold: " + p.gold);
        System.out.println("Weapon: " + p.weapon.name + "    Armor: " + (p.armor == null ? "None" : p.armor.name));
        System.out.println(LINE);
        for (int i = 0; i < STAT_NAMES.length; i++) {
            int total = p.totalStat(i);
            int bonus = total - p.scores[i];
            int mod = p.mod(i);
            String detail = bonus != 0 ? String.format(" [%d%+d]", p.scores[i], bonus) : "";
            System.out.println(STAT_NAMES[i] + ": " + total + detail + " (" + (mod > 0 ? "+" : "") + mod + ")");
        }
        System.out.println(LINE);
        System.out.printf("Attack bonus: +%d physical, +%d magic%n", 2 * p.totalStat(STR), 2 * p.totalStat(INT));
        System.out.printf("Defense: %d   Armor Pen: %d   Con deduction: -%d   Damage Reduction: %d%n",
                p.defense(), p.armorPen(), Math.round(1.5 * p.totalStat(CON)), p.damageReduction());
        System.out.printf("HP regen: %d per turn%s   Crit chance: %.0f%%   Turn priority (Dex): %d%n",
                p.regen(), p.regen() == 0 ? " (needs a positive Con modifier)" : "", p.critRate() * 100, p.totalStat(DEX));

        String skills = "";
        for (int i = 0; i < SKILLS.length; i++) {
            if (p.knownSkills[i]) skills += (skills.isEmpty() ? "" : ", ") + SKILLS[i].name;
        }
        System.out.println("Skills: " + (skills.isEmpty() ? "None" : skills));
        System.out.printf("Evasion: %.0f%% (x%d while dodging)%n", Math.min(0.90, p.baseEvasion()) * 100, (int) DODGE_EVASION_MULT);
        String clearedList = "";
        for (int i = 0; i < LOCALES.length; i++) {
            if (p.cleared[i]) clearedList += (clearedList.isEmpty() ? "" : ", ") + LOCALES[i].name;
        }
        System.out.println("Locale: " + LOCALES[p.locale].name + "   Cleared: " + (clearedList.isEmpty() ? "None" : clearedList));
        System.out.println("Unspent skill points: " + p.skillPoints);
        System.out.printf("Encounters cleared since the last boss: %d/%d   Bosses defeated: %d%n",
                p.encountersCleared, BOSS_INTERVAL, p.bossesDefeated);
    }

    static void showMechanics() {
        say(LINE,
            "BASE STATS (modifier = floor((total - 10) / 2)):",
            "Strength -> +2 damage per point of TOTAL Strength (physical attacks)",
            "Dexterity -> Higher TOTAL Dex acts first; modifier gives +2% hit/retreat chance per point;",
            "                1% evasion per point of TOTAL Dex (see Dodge)",
            "Constitution -> -1.5 damage taken per point of TOTAL Con (rounded);",
            "                +5 max HP and +1 HP regen per turn per positive modifier point",
            "                (no bonus if the modifier is 0 or negative; poison stops regen)",
            "Intelligence -> +2 damage per point for magic; detects weaknesses faster;",
            "                +5% skill learn chance per point; makes items stronger",
            "Wisdom -> Improves item finds while exploring",
            "Luck -> Increases critical chance (1% base, +1% per positive modifier point)",
            LINE,
            "DAMAGE ORDER:",
            "move power + roll + 2 x stat, then position, Charge (x2), crit (x2),",
            "weakness (x1.5) / resistance (x0.5) and guard (x0.5).",
            "Then flat reductions: Defense (minus attacker Armor Pen), Con deduction,");
        System.out.println("and Damage Reduction (max " + MAX_DAMAGE_REDUCTION + "). Every hit deals at least 1.");
        say(LINE,
            "DAMAGE TYPES:",
            "Weapons deal physical damage (scales with Strength): SLASH, PIERCE or BLUNT.",
            "Each weapon has a main type, but individual moves can differ.",
            "Magic skills deal FIRE, ICE or LIGHTNING (scales with Intelligence).",
            LINE,
            "STATUS EFFECTS (they wear off after battle; 20% chance to fade each turn):",
            "Bleed -> -2 HP per turn, +1 more each turn it lasts. Cured by Potions.",
            "Poison -> -4 HP per turn and no HP regen. Cured by Antidotes.",
            "Burn -> -3 HP per turn, defense -15% (-25% and +5 damage if weak to fire).",
            "        Cured by Water Flasks. Fireproof armor is immune.",
            "Frostbite -> -2 HP per turn and Dexterity -10%. Cured by Warm Drinks.",
            "        Iceproof armor (or an ICE resistance) is immune.",
            "Static -> -1 HP per turn, 30% chance each turn to be paralyzed and lose the turn.",
            "        Cured by Warm Drinks. Insulated armor (or a LIGHTNING resistance) is immune.",
            "Fireproof armor / a FIRE resistance blocks burning too.",
            LINE,
            "COMBAT:",
            "Attack -> Weapon moves, Charge, and learned skills (limited uses per battle)",
            "Charge -> No damage; your next attack that lands deals double damage",
            "Guard -> Halves the next hit you take",
            "Dodge -> Triples your evasion until your next turn (lasts as long as Guard)",
            "Item -> Potions, thrown items (15 damage + 30% status), cures, Shock Trap,",
            "        Smoke Bomb (enemy attacks miss for 2 turns) and Flashbomb (enemy accuracy -30%).",
            "        Smoke Bomb and Flashbomb have a 5 turn cooldown once their effect ends.",
            "Ranged weapons -> The bow and staff ignore the Back position's damage penalty.",
            "Staff -> Magic attacks (Intelligence), including Concentrated Beam (pierce) and Galeslash (slash).",
            "        You start with the staff instead of the sword if your total INT beats your total STR.",
            "Position -> Front hits harder but takes more; Back is safer but weaker",
            "Retreat -> Chance to flee the fight",
            "Fights can have several enemies; everyone acts in order of Dexterity.",
            "Enemies have hidden weaknesses/resistances. Hitting them reveals them,",
            "and Intelligence helps you spot them each turn.",
            LINE,
            "LOCALES:");
        System.out.println("Each locale has its own enemies. After every " + BOSS_INTERVAL + " encounters you clear (a whole group counts as one), its boss");
        System.out.println("appears on your next explore. Attack its body parts: damage to a part also drains");
        System.out.println("its main HP, and breaking parts weakens the boss.");
        System.out.println("Defeating a boss clears the locale: +" + STAT_GAIN_MIN + " to +" + STAT_GAIN_MAX + " on EVERY stat (rolled per stat),");
        say("and you move on to the next locale.",
            "Forest -> Snowy Peaks -> Stormspire -> The Calamitous Alatar (final boss only).",
            "Beat the Calamitatum Rex to finish the game.",
            LINE,
            "LEVELS AND ENEMY SCALING:",
            "Every victorious battle is a level up. Enemies grow tougher as you level, so",
            "grinding never makes the game trivial. A poor starting stat roll makes the early game",
            "harder, but enemies ease off a little as you level; a great roll makes the early game",
            "easier, but enemies push back harder in the late game. Gear is never counted.",
            LINE,
            "SKILL POINTS:",
            "You earn 1 skill point per victorious battle and choose which stat to raise.",
            LINE,
            "EXPLORING:",
            "[1] Nothing happens  [2] You find an item  [3] A fight begins  [4] A shop appears",
            "Defeating enemies earns gold. Spend it at shops on items, weapons, armor,",
            "spellbooks (magic) and scrolls (physical) to learn skills.");
    }

    static void showCredits() {
        say(LINE,
            "                  CREDITS                 ",
            LINE);
    }

    // ==================================================================
    // Adventure hub: explore, inventory, equipment, shop
    // ==================================================================
    static void adventure(Player p) {
        boolean inGame = true;
        while (inGame && p.isAlive() && !p.gameComplete) {
            Locale here = LOCALES[p.locale];
            System.out.println(LINE);
            System.out.printf("ADVENTURE (%s) - %s   Lv %d   HP %d/%d   Gold %d%n", here.name, p.name, p.level(), p.hp, p.maxHp, p.gold);
            String progress = p.cleared[p.locale] ? "Locale cleared"
                    : here.bossOnly ? "The boss awaits"
                    : "Boss in: " + Math.max(0, BOSS_INTERVAL - p.encountersCleared) + " encounters";
            System.out.printf("Weapon: %s   Armor: %s   %s%n", p.weapon.name, p.armor == null ? "None" : p.armor.name, progress);
            System.out.println("  [1] Explore  [2] Inventory  [3] Equipment  [4] Character  [5] Skill Points"
                    + (p.skillPoints > 0 ? " (" + p.skillPoints + ")" : "") + "  [0] Main Menu");

            switch (readInt(0, 5)) {
                case 1: explore(p); break;
                case 2: inventoryMenu(p); break;
                case 3: equipmentMenu(p); break;
                case 4: showCharacter(p); pause(); break;
                case 5:
                    if (p.skillPoints > 0) spendSkillPoints(p);
                    else System.out.println("You have no skill points to spend. You earn 1 for every victorious battle.");
                    break;
                case 0:
                    System.out.println("Returning to the main menu. Choose CONTINUE to resume.");
                    inGame = false;
                    break;
            }
        }
    }

    /** Spends skill points: +1 to the base score of any stat. */
    static void spendSkillPoints(Player p) {
        String[] hints = {
            "+2 physical damage", "+1% evasion, acts earlier", "-1.5 damage taken (and HP/regen)",
            "+2 magic damage, learn skills", "better item finds", "crit chance"
        };
        while (p.skillPoints > 0) {
            System.out.println(LINE);
            System.out.println("SKILL POINTS: " + p.skillPoints + " unspent. Choose a stat to raise by 1:");
            for (int i = 0; i < STAT_NAMES.length; i++) {
                System.out.printf("  [%d] %-13s %2d (total %2d, mod %+d)  %s%n", i + 1, STAT_NAMES[i], p.scores[i],
                        p.totalStat(i), p.mod(i), hints[i]);
            }
            System.out.println("  [0] Save for later");
            int choice = readInt(0, STAT_NAMES.length);
            if (choice == 0) return;

            int stat = choice - 1;
            p.scores[stat]++;
            p.skillPoints--;
            System.out.println(STAT_NAMES[stat] + " increased to " + p.scores[stat] + "!");
            if (stat == CON) {
                int oldMax = p.maxHp;
                p.recalcMaxHp();
                if (p.maxHp > oldMax) {
                    p.heal(p.maxHp - oldMax);
                    System.out.println("Max HP +" + (p.maxHp - oldMax) + " (now " + p.maxHp + ")");
                }
            }
        }
    }

    /** Event generator: 1 = nothing happens, 2 = item found, 3 = fight, 4 = shop. */
    static void explore(Player p) {
        System.out.println(LINE);

        // Health regen also works while travelling.
        if (p.regen() > 0 && p.hp < p.maxHp) {
            p.heal(p.regen());
            System.out.println("You regenerate " + p.regen() + " HP as you travel.");
        }

        // The boss ambushes you once enough enemies have fallen (until its locale is cleared).
        if ((p.encountersCleared >= BOSS_INTERVAL || LOCALES[p.locale].bossOnly) && !p.cleared[p.locale]) {
            Boss boss = createBoss(p.locale);
            scaleEnemy(boss, p);
            System.out.println(boss.introText());
            resolveFight(p, new Fighter[] { boss }, true);
            return;
        }
        Locale here = LOCALES[p.locale];

        int fightChance = 30;
        int shopChance = 10;
        int itemChance = Math.max(15, Math.min(35, 25 + p.mod(WIS) * 2)); // Wisdom helps find items
        int roll = RNG.nextInt(100);

        int event;
        if (roll < fightChance) event = 3;
        else if (roll < fightChance + shopChance) event = 4;
        else if (roll < fightChance + shopChance + itemChance) event = 2;
        else event = 1;

        switch (event) {
            case 1:
                System.out.println(here.quiet[RNG.nextInt(here.quiet.length)]);
                break;
            case 2:
                findLoot(p);
                break;
            case 4:
                shop(p);
                break;
            default:
                resolveFight(p, spawnEncounter(here.enemies[RNG.nextInt(here.enemies.length)], p), false);
                break;
        }
    }

    /** Runs a fight and handles rewards, boss progress and game over. */
    static void resolveFight(Player p, Fighter[] group, boolean boss) {
        Outcome result = fight(p, group);

        if (result == Outcome.LOSE) {
            System.out.println("\nDefeat... " + p.name + " has fallen. GAME OVER.");
            pause();
            return;
        }

        int gold = 0;
        for (Fighter f : group) {
            if (!f.isAlive()) gold += f.goldReward;
        }
        p.gold += gold;

        if (result == Outcome.FLED) {
            System.out.println("\nYou escaped successfully!");
            if (gold > 0) System.out.println("You managed to loot " + gold + " gold first (total " + p.gold + ").");
        } else {
            int recovered = Math.max(1, p.maxHp * 15 / 100);
            p.heal(recovered);
            System.out.println("\nVictory!");
            System.out.println("You loot " + gold + " gold (total " + p.gold + ") and recover " + recovered + " HP.");
            p.victories++;
            p.skillPoints++;
            System.out.println("LEVEL UP! You are now level " + p.level() + " and earned 1 skill point!");
            spendSkillPoints(p);
        }

        if (boss) {
            if (result == Outcome.WIN) {
                clearLocale(p, (Boss) group[0]);
            } else {
                System.out.println("The " + group[0].name + " is still out there. It will find you again...");
            }
        } else if (result == Outcome.WIN && !p.cleared[p.locale]) {
            // Only a fully cleared encounter counts (a whole group is one encounter; fleeing counts for nothing).
            p.encountersCleared++;
            if (p.encountersCleared >= BOSS_INTERVAL) {
                System.out.println("\nSomething powerful stirs nearby. The boss of the " + LOCALES[p.locale].name + " is hunting you!");
                System.out.println("It will appear the next time you explore.");
            } else {
                System.out.printf("Encounters cleared since the last boss: %d/%d%n", p.encountersCleared, BOSS_INTERVAL);
            }
        }
    }

    /** Defeating a locale's boss clears it: +1..+6 on every stat, then the player moves on to the next locale. */
    static void clearLocale(Player p, Boss boss) {
        Locale done = LOCALES[p.locale];
        p.cleared[p.locale] = true;
        p.encountersCleared = 0;
        p.bossesDefeated++;

        System.out.println();
        System.out.println(boss.defeatText());
        System.out.println("*** " + done.name.toUpperCase() + " CLEARED! ***");

        // Clearing the last locale finishes the game.
        if (p.locale + 1 >= LOCALES.length) {
            System.out.println();
            say(LINE,
                "Congratulations on beating the game! We hope you enjoyed playing it as much as we enjoyed making it.",
                LINE);
            p.gameComplete = true;
            pause();
            return;
        }

        System.out.println("The victory sharpens you. Every stat increases:");

        int oldMax = p.maxHp;
        for (int i = 0; i < STAT_NAMES.length; i++) {
            int gain = STAT_GAIN_MIN + RNG.nextInt(STAT_GAIN_MAX - STAT_GAIN_MIN + 1);
            p.scores[i] += gain;
            System.out.printf("  %-13s +%d  (now %d)%n", STAT_NAMES[i], gain, p.scores[i]);
        }
        p.recalcMaxHp();
        if (p.maxHp > oldMax) {
            p.heal(p.maxHp - oldMax);
            System.out.println("  Max HP +" + (p.maxHp - oldMax) + " (now " + p.maxHp + ")");
        }

        if (p.locale + 1 < LOCALES.length) {
            p.locale++;
            Locale next = LOCALES[p.locale];
            System.out.println();
            System.out.println("=== NEW LOCALE: " + next.name.toUpperCase() + " ===");
            System.out.println(next.intro);
            if (next.welcomeItem != null) {
                if (p.inventory.addItem(next.welcomeItem, next.welcomeQty)) {
                    System.out.println("A traveller hands you " + next.welcomeQty + " " + next.welcomeItem.name + "s for the road ahead.");
                }
            }
        } else {
            System.out.println("\nThere are no more locales to explore - you have cleared them all!");
        }
    }

    static void findLoot(Player p) {
        Inventory inv = p.inventory;
        int roll = RNG.nextInt(100);
        if (roll < 55) { // 55%: potion/bomb/antidote/water
            giveFoundItem(inv, ITEMS[RNG.nextInt(BASIC_ITEMS)]);
        } else if (roll < 75) { // 20%: spellbook/scroll
            giveFoundItem(inv, ITEMS[BASIC_ITEMS + RNG.nextInt(ITEMS.length - BASIC_ITEMS)]);
        } else if (roll < 90) { // 15%: weapon
            Weapon w = WEAPONS[RNG.nextInt(WEAPONS.length)];
            if (inv.hasWeapon(w)) {
                System.out.println("You found a " + w.name + ", but you already own one.");
            } else if (inv.addWeapon(w)) {
                System.out.println("You found a " + w.name + "! Equip it from the Equipment menu.");
            } else {
                System.out.println("You found a " + w.name + ", but you can't carry any more weapons.");
            }
        } else { // 10%: armor
            Armor a = ARMORS[RNG.nextInt(ARMORS.length)];
            if (inv.hasArmor(a)) {
                System.out.println("You found a " + a.name + ", but you already own one.");
            } else if (inv.addArmor(a)) {
                System.out.println("You found a " + a.name + "! Equip it from the Equipment menu.");
            } else {
                System.out.println("You found a " + a.name + ", but you can't carry any more armor.");
            }
        }
    }

    static void giveFoundItem(Inventory inv, Item item) {
        if (inv.addItem(item, 1)) {
            System.out.println("You found a " + item.name + "! It was added to your inventory.");
        } else {
            System.out.println("You found a " + item.name + ", but your inventory is full.");
        }
    }

    // ---------------- Inventory and equipment menus ----------------

    static void inventoryMenu(Player p) {
        Inventory inv = p.inventory;
        while (true) {
            System.out.println(LINE);
            System.out.println("INVENTORY   Gold: " + p.gold);
            if (inv.itemCount == 0) {
                System.out.println("Your pouch is empty.");
                return;
            }
            for (int i = 0; i < inv.itemCount; i++) {
                Item it = inv.items[i];
                String info;
                if (it.isBook()) {
                    info = String.format("learn %s, %.0f%% chance%s", SKILLS[it.skillIndex].name, p.learnChance() * 100,
                            p.knownSkills[it.skillIndex] ? ", already known" : "");
                } else {
                    info = describeItem(it, p.potency());
                }
                System.out.printf("  [%d] %s x%d (%s)%n", i + 1, it.name, inv.counts[i], info);
            }
            System.out.println("  [0] Back");

            int choice = readInt(0, inv.itemCount);
            if (choice == 0) return;

            Item item = inv.items[choice - 1];
            if (item.isBook()) {
                studyBook(p, choice - 1);
            } else if (item.type == ItemType.HEAL || item.type == ItemType.MAXHEAL) {
                if (p.hp == p.maxHp) {
                    System.out.println("You are already at full HP.");
                } else {
                    int amount = item.type == ItemType.MAXHEAL ? p.maxHp - p.hp : item.value + p.potency();
                    inv.removeOne(choice - 1);
                    p.heal(amount);
                    System.out.printf("You use %s and recover %d HP. (%d/%d HP)%n", item.name, amount, p.hp, p.maxHp);
                }
            } else if (item.type == ItemType.ANTIDOTE || item.type == ItemType.WATER || item.type == ItemType.WARM) {
                System.out.println("You have no ailment to cure right now. (Status effects only last during battle.)");
            } else {
                System.out.println(item.name + " can only be used in battle.");
            }
        }
    }

    /** Spellbooks and scrolls are consumed whether or not the attempt succeeds. */
    static void studyBook(Player p, int slot) {
        Item book = p.inventory.items[slot];
        int skill = book.skillIndex;
        if (p.knownSkills[skill]) {
            System.out.println("You already know " + SKILLS[skill].name + ".");
            return;
        }
        p.inventory.removeOne(slot);
        if (RNG.nextDouble() < p.learnChance()) {
            p.knownSkills[skill] = true;
            System.out.println("Success! You learned " + SKILLS[skill].name + "!");
        } else {
            System.out.println("You fail to grasp it, and the " + book.name + " crumbles to dust.");
        }
    }

    static void equipmentMenu(Player p) {
        Inventory inv = p.inventory;
        while (true) {
            say(LINE,
                "EQUIPMENT",
                "Weapons:");
            for (int i = 0; i < inv.weaponCount; i++) {
                Weapon w = inv.weapons[i];
                System.out.printf("  [%d] %s%s  [%s, Armor Pen %d]%n", i + 1, w.name, w == p.weapon ? " (equipped)" : "", w.label(), w.armorPen);
                System.out.println("      Moves: " + w.moveList());
            }
            System.out.println("Armor:");
            for (int i = 0; i < inv.armorCount; i++) {
                Armor a = inv.armors[i];
                System.out.printf("  [%d] %s%s%n", inv.weaponCount + i + 1, a.name, a == p.armor ? " (equipped)" : "");
                System.out.println("      " + a.info());
            }
            System.out.println("  [0] Back");

            int choice = readInt(0, inv.weaponCount + inv.armorCount);
            if (choice == 0) return;

            if (choice <= inv.weaponCount) {
                p.weapon = inv.weapons[choice - 1];
                System.out.println("You equip the " + p.weapon.name + ".");
            } else {
                p.armor = inv.armors[choice - inv.weaponCount - 1];
                System.out.println("You put on the " + p.armor.name + ".");
            }
        }
    }

    // ---------------- Shop ----------------
    // Offer index: 0..ITEMS-1 = items, then weapons, then armor.

    static int offerCount() {
        return ITEMS.length + WEAPONS.length + ARMORS.length;
    }

    static String offerName(int idx) {
        if (idx < ITEMS.length) return ITEMS[idx].name;
        idx -= ITEMS.length;
        if (idx < WEAPONS.length) return WEAPONS[idx].name;
        return ARMORS[idx - WEAPONS.length].name;
    }

    static int offerPrice(int idx) {
        if (idx < ITEMS.length) return ITEMS[idx].price;
        idx -= ITEMS.length;
        if (idx < WEAPONS.length) return WEAPONS[idx].price;
        return ARMORS[idx - WEAPONS.length].price;
    }

    static String offerInfo(int idx) {
        if (idx < ITEMS.length) return describeItem(ITEMS[idx], 0);
        idx -= ITEMS.length;
        if (idx < WEAPONS.length) return WEAPONS[idx].label() + " weapon, Armor Pen " + WEAPONS[idx].armorPen + "; " + WEAPONS[idx].moveList();
        return ARMORS[idx - WEAPONS.length].info();
    }

    static boolean offerOwned(Player p, int idx) {
        if (idx < ITEMS.length) return false;
        idx -= ITEMS.length;
        if (idx < WEAPONS.length) return p.inventory.hasWeapon(WEAPONS[idx]);
        return p.inventory.hasArmor(ARMORS[idx - WEAPONS.length]);
    }

    /** Puts the purchase in the inventory; returns false if there was no room. */
    static boolean offerGive(Player p, int idx) {
        if (idx < ITEMS.length) return p.inventory.addItem(ITEMS[idx], 1);
        idx -= ITEMS.length;
        if (idx < WEAPONS.length) return p.inventory.addWeapon(WEAPONS[idx]);
        return p.inventory.addArmor(ARMORS[idx - WEAPONS.length]);
    }

    static void shop(Player p) {
        System.out.println("You meet a travelling merchant. \"Take a look at my wares!\"");

        int total = offerCount();
        int[] stock = new int[SHOP_SLOTS];
        int[] left = new int[SHOP_SLOTS];
        boolean[] picked = new boolean[total];
        for (int i = 0; i < SHOP_SLOTS; i++) {
            int idx;
            do {
                idx = RNG.nextInt(total);
            } while (picked[idx]);
            picked[idx] = true;
            stock[i] = idx;
            left[i] = idx < ITEMS.length ? 3 : 1; // consumables: 3 in stock, gear: 1
        }

        while (true) {
            System.out.println(LINE);
            System.out.println("SHOP   Gold: " + p.gold);
            for (int i = 0; i < SHOP_SLOTS; i++) {
                String note = left[i] == 0 ? "SOLD OUT" : (offerOwned(p, stock[i]) ? "OWNED" : "x" + left[i]);
                System.out.printf("  [%d] %-26s %4dg  %s  [%s]%n", i + 1, offerName(stock[i]), offerPrice(stock[i]),
                        offerInfo(stock[i]), note);
            }
            System.out.println("  [0] Leave");

            int choice = readInt(0, SHOP_SLOTS);
            if (choice == 0) {
                System.out.println("\"Safe travels!\"");
                return;
            }

            int slot = choice - 1;
            int idx = stock[slot];
            int price = offerPrice(idx);
            if (left[slot] == 0) {
                System.out.println("That's sold out.");
            } else if (offerOwned(p, idx)) {
                System.out.println("You already own that.");
            } else if (p.gold < price) {
                System.out.println("You can't afford that. (" + price + " gold needed)");
            } else if (!offerGive(p, idx)) {
                System.out.println("You have no room to carry that.");
            } else {
                p.gold -= price;
                left[slot]--;
                System.out.println("You bought " + offerName(idx) + " for " + price + " gold.");
            }
        }
    }

    // ==================================================================
    // Combat
    // ==================================================================
    static boolean anyAlive(Fighter[] group) {
        for (Fighter f : group) {
            if (f.isAlive()) return true;
        }
        return false;
    }

    /** Everyone acts in order of total Dexterity (highest first). Ties keep the player ahead. */
    static Fighter[] turnOrder(Player p, Fighter[] enemies) {
        Fighter[] order = new Fighter[enemies.length + 1];
        order[0] = p;
        for (int i = 0; i < enemies.length; i++) {
            order[i + 1] = enemies[i];
        }
        for (int i = 1; i < order.length; i++) {
            Fighter key = order[i];
            int j = i - 1;
            while (j >= 0 && order[j].totalStat(DEX) < key.totalStat(DEX)) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
        return order;
    }

    static Outcome fight(Player p, Fighter[] enemies) {
        p.position = Position.FRONT;
        p.clearStatuses();
        p.resetSkillUses();
        p.smokeCooldown = 0;
        p.flashTimer = 0;
        p.flashCooldown = 0;
        arenaEffect = null;
        for (Fighter e : enemies) {
            e.position = Position.FRONT;
            e.clearStatuses();
        }

        if (enemies.length == 1) {
            System.out.printf("%s appears!%n", enemies[0].name);
        } else {
            String names = "";
            for (int i = 0; i < enemies.length; i++) {
                names += (i > 0 ? ", " : "") + enemies[i].name;
            }
            System.out.printf("A group appears: %s!%n", names);
        }

        Fighter[] order = turnOrder(p, enemies);
        String orderText = "";
        for (int i = 0; i < order.length; i++) {
            orderText += (i > 0 ? " > " : "") + order[i].name + " (Dex " + order[i].totalStat(DEX) + ")";
        }
        System.out.println("Turn order: " + orderText);

        boolean fled = false;
        while (!fled && p.isAlive() && anyAlive(enemies)) {
            for (int i = 0; i < order.length; i++) {
                Fighter f = order[i];
                if (!p.isAlive() || !anyAlive(enemies)) break;
                if (!f.isAlive()) continue;

                if (f == p) {
                    if (playerTurn(p, enemies)) {
                        fled = true;
                        break;
                    }
                } else if (f instanceof Boss) {
                    bossTurn((Boss) f, p);
                } else {
                    enemyTurn(f, p);
                }
                if (f != p && f.flashTurns > 0) f.flashTurns--; // Flashbomb counts the monster's own turns
            }
        }

        p.clearStatuses();
        for (Fighter e : enemies) {
            e.clearStatuses();
        }
        arenaEffect = null;
        if (fled) return Outcome.FLED;

        printStatus(p, enemies);
        return p.isAlive() ? Outcome.WIN : Outcome.LOSE;
    }

    // ---------------- Damage over time, regen, statuses ----------------

    /** Start of a fighter's turn: damage over time, natural cures, then health regen. */
    static void startTurn(Fighter f) {
        if (f.bleeding) {
            int dmg = f.bleedDamage;
            f.takeDamage(dmg);
            System.out.printf("%s bleeds for %d damage. (%d/%d HP)%n", f.name, dmg, f.hp, f.maxHp);
            f.bleedDamage++;
            if (f.isAlive() && RNG.nextInt(100) < NATURAL_CURE_CHANCE) {
                f.bleeding = false;
                f.bleedDamage = BLEED_START;
                System.out.println(f.name + "'s bleeding stops.");
            }
        }
        if (f.poisoned && f.isAlive()) {
            f.takeDamage(POISON_DAMAGE);
            System.out.printf("%s suffers %d poison damage. (%d/%d HP)%n", f.name, POISON_DAMAGE, f.hp, f.maxHp);
            if (f.isAlive() && RNG.nextInt(100) < NATURAL_CURE_CHANCE) {
                f.poisoned = false;
                System.out.println("The poison in " + f.name + "'s blood fades.");
            }
        }
        if (f.burning && f.isAlive()) {
            int dmg = BURN_DAMAGE + (f.weakness == Element.FIRE ? BURN_WEAK_BONUS : 0);
            f.takeDamage(dmg);
            System.out.printf("%s burns for %d damage. (%d/%d HP)%n", f.name, dmg, f.hp, f.maxHp);
            if (f.isAlive() && RNG.nextInt(100) < NATURAL_CURE_CHANCE) {
                f.burning = false;
                System.out.println("The flames on " + f.name + " die out.");
            }
        }
        if (f.frostbitten && f.isAlive()) {
            f.takeDamage(FROSTBITE_DAMAGE);
            System.out.printf("%s suffers %d frostbite damage. (%d/%d HP)%n", f.name, FROSTBITE_DAMAGE, f.hp, f.maxHp);
            if (f.isAlive() && RNG.nextInt(100) < NATURAL_CURE_CHANCE) {
                f.frostbitten = false;
                System.out.println("The frostbite on " + f.name + " thaws.");
            }
        }
        if (f.shocked && f.isAlive()) {
            f.takeDamage(STATIC_DAMAGE);
            System.out.printf("%s is jolted by static for %d damage. (%d/%d HP)%n", f.name, STATIC_DAMAGE, f.hp, f.maxHp);
            if (f.isAlive() && RNG.nextInt(100) < STATIC_PARALYZE_CHANCE) {
                f.stunned = true;
                System.out.println(f.name + " is paralyzed and will lose a turn!");
            }
            if (f.isAlive() && RNG.nextInt(100) < NATURAL_CURE_CHANCE) {
                f.shocked = false;
                System.out.println("The static around " + f.name + " dissipates.");
            }
        }
        if (!f.isAlive()) {
            System.out.println(f.name + " collapses!");
            return;
        }
        int regen = f.regen();
        if (regen > 0 && !f.poisoned && f.hp < f.maxHp) {
            f.heal(regen);
            System.out.printf("%s regenerates %d HP. (%d/%d HP)%n", f.name, regen, f.hp, f.maxHp);
        }
    }

    /** Fireproof/iceproof/insulated armor, or a resistance to the element, makes a fighter immune. */
    static boolean immuneTo(Fighter t, Status s) {
        Element res = t.activeResistance();
        switch (s) {
            case BURN: return (t.armor != null && t.armor.fireproof) || res == Element.FIRE;
            case FROSTBITE: return (t.armor != null && t.armor.iceproof) || res == Element.ICE;
            case STATIC: return (t.armor != null && t.armor.insulated) || res == Element.LIGHTNING;
            default: return false;
        }
    }

    static void applyStatus(Fighter target, Status status) {
        if (immuneTo(target, status)) {
            System.out.println("  " + target.name + " is immune to " + status.toString().toLowerCase() + "!");
            return;
        }
        switch (status) {
            case BLEED:
                if (!target.bleeding) {
                    target.bleeding = true;
                    target.bleedDamage = BLEED_START;
                }
                System.out.println("  " + target.name + " is bleeding!");
                break;
            case POISON:
                target.poisoned = true;
                System.out.println("  " + target.name + " is poisoned! HP regeneration is disabled.");
                break;
            case BURN:
                target.burning = true;
                System.out.println("  " + target.name + " catches fire! Defense is reduced.");
                break;
            case FROSTBITE:
                target.frostbitten = true;
                System.out.println("  " + target.name + " is frostbitten! Dexterity is reduced by 10%.");
                break;
            case STATIC:
                target.shocked = true;
                System.out.println("  " + target.name + " is shocked by static! (-1 HP per turn, may be paralyzed)");
                break;
        }
    }

    static String statusText(Fighter f) {
        String text = "";
        if (f.bleeding) text += " (Bleeding -" + f.bleedDamage + ")";
        if (f.poisoned) text += " (Poisoned)";
        if (f.burning) text += " (Burning)";
        if (f.frostbitten) text += " (Frostbitten)";
        if (f.shocked) text += " (Static)";
        if (f.smokeTurns > 0) text += " (Smoke " + f.smokeTurns + ")";
        if (f.flashTurns > 0) text += " (Flashed " + f.flashTurns + ")";
        if (f.shockTrap) text += " (Shock Trap)";
        if (f.stunned) text += " (Stunned)";
        return text;
    }

    // ---------------- Player turn ----------------

    /** Runs the player's turn. Returns true if the player escaped. */
    static boolean playerTurn(Player p, Fighter[] enemies) {
        p.guarding = false; // guard and dodge last until the player's next turn
        p.dodging = false;

        // Smoke Bomb / Flashbomb timers (the cooldown starts when the effect ends)
        if (p.smokeTurns > 0) {
            p.smokeTurns--;
            if (p.smokeTurns == 0) {
                p.smokeCooldown = SMOKE_COOLDOWN;
                System.out.println("The smoke around you clears.");
            }
        } else if (p.smokeCooldown > 0) {
            p.smokeCooldown--;
        }
        if (p.flashTimer > 0) {
            p.flashTimer--;
            if (p.flashTimer == 0) p.flashCooldown = FLASH_COOLDOWN;
        } else if (p.flashCooldown > 0) {
            p.flashCooldown--;
        }

        System.out.println();
        startTurn(p);
        if (!p.isAlive()) return false;
        if (p.stunned) {
            p.stunned = false;
            System.out.println(p.name + " can't act this turn!");
            return false;
        }

        printStatus(p, enemies);
        tryDetect(p, enemies);

        boolean turnUsed = false;
        while (!turnUsed) {
            System.out.println("\nYour turn:");
            System.out.println("  [1] Attack  [2] Guard  [3] Dodge  [4] Item  [5] Position  [6] Retreat");

            switch (readInt(1, 6)) {
                case 1: turnUsed = playerAttack(p, enemies); break;
                case 2: turnUsed = playerGuard(p); break;
                case 3: turnUsed = playerDodge(p); break;
                case 4: turnUsed = playerItem(p, enemies); break;
                case 5: turnUsed = playerPosition(p); break;
                case 6:
                    if (tryRetreat(p)) return true;
                    turnUsed = true;
                    break;
            }
        }
        return false;
    }

    /** Intelligence: each turn there is a chance to reveal one hidden affinity or boss weak point. */
    static void tryDetect(Player p, Fighter[] enemies) {
        Fighter target = null;
        BodyPart part = null;
        for (Fighter e : enemies) {
            if (!e.isAlive()) continue;
            if (e instanceof Boss) {
                Boss b = (Boss) e;
                for (BodyPart bp : b.parts) {
                    if (!bp.known && !bp.broken()) {
                        target = e;
                        part = bp;
                        break;
                    }
                }
            } else if (!e.weaknessKnown || !e.resistanceKnown) {
                target = e;
            }
            if (target != null) break;
        }
        if (target == null) return;

        double chance = Math.min(0.95, 0.10 + 0.03 * p.totalStat(INT));
        if (RNG.nextDouble() >= chance) return;

        if (part != null) {
            part.known = true;
            System.out.println("Your insight reveals the " + target.name + "'s " + part.name + " is weak to " + part.weakText() + "!");
        } else if (!target.weaknessKnown) {
            target.weaknessKnown = true;
            if (target.weakness == null) System.out.println("Your insight tells you " + target.name + " has no obvious weakness.");
            else System.out.println("Your insight reveals that " + target.name + " is weak to " + target.weakness + "!");
        } else {
            target.resistanceKnown = true;
            if (target.resistance == null) System.out.println("Your insight tells you " + target.name + " has no resistances.");
            else System.out.println("Your insight reveals that " + target.name + " resists " + target.resistance + ".");
        }
    }

    static String affinityText(Element element, boolean known) {
        if (!known) return "???";
        return element == null ? "None" : element.toString();
    }

    static void printFighterLine(Fighter f) {
        System.out.printf("%-18s HP %3d/%-3d  [%s]%s%s%s%s%s%n", f.name, f.hp, f.maxHp, f.position,
                f.guarding ? " (Guarding)" : "", f.dodging ? " (Dodging)" : "", f.charged ? " (Charged)" : "",
                (f instanceof Boss && ((Boss) f).enraged) ? " (ENRAGED)" : "", statusText(f));
    }

    static void printStatus(Player p, Fighter[] enemies) {
        System.out.println("\n==============================================");
        printFighterLine(p);
        System.out.println("----------------------------------------------");
        for (Fighter e : enemies) {
            if (!e.isAlive()) continue;
            printFighterLine(e);
            if (e instanceof Boss) {
                Boss b = (Boss) e;
                System.out.println("  Body parts (damage to a part also drains the main HP):");
                for (BodyPart bp : b.parts) {
                    System.out.printf("    %-13s %3d/%-3d  weak: %s%s%n", bp.name, bp.hp, bp.maxHp, bp.weakText(),
                            bp.broken() ? "  [DESTROYED]" : "");
                }
            } else {
                System.out.printf("  Weakness: %s | Resistance: %s%n",
                        affinityText(e.weakness, e.weaknessKnown), affinityText(e.resistance, e.resistanceKnown));
            }
        }
        if (arenaEffect != null) {
            System.out.println("  ARENA: " + arenaName(arenaEffect) + " - all " + arenaEffect
                    + " attacks deal +20% damage and have +10% status chance (everyone)");
        }
        System.out.println("==============================================");
    }

    /** Picks which enemy to hit (skipped when only one is alive). Returns null if the player backs out. */
    static Fighter chooseTarget(Fighter[] enemies) {
        int alive = 0;
        Fighter only = null;
        for (Fighter e : enemies) {
            if (e.isAlive()) {
                alive++;
                only = e;
            }
        }
        if (alive == 1) return only;

        Fighter[] list = new Fighter[alive];
        int n = 0;
        System.out.println("Choose a target:");
        for (Fighter e : enemies) {
            if (e.isAlive()) {
                list[n++] = e;
                System.out.printf("  [%d] %s (HP %d/%d)%n", n, e.name, e.hp, e.maxHp);
            }
        }
        System.out.println("  [0] Back");
        int choice = readInt(0, n);
        return choice == 0 ? null : list[choice - 1];
    }

    /** Picks a boss body part. Returns -1 for the body itself, 0..4 for a part, -2 to go back. */
    static int choosePart(Boss b) {
        while (true) {
            System.out.println("Aim at:");
            System.out.printf("  [1] Body (main HP %d/%d)%n", b.hp, b.maxHp);
            for (int i = 0; i < b.parts.length; i++) {
                BodyPart bp = b.parts[i];
                System.out.printf("  [%d] %s (%d/%d HP) weak: %s%s%n", i + 2, bp.name, bp.hp, bp.maxHp, bp.weakText(),
                        bp.broken() ? "  [DESTROYED]" : "");
            }
            System.out.println("  [0] Back");
            int choice = readInt(0, b.parts.length + 1);
            if (choice == 0) return -2;
            if (choice == 1) return -1;
            if (b.parts[choice - 2].broken()) {
                System.out.println("That part is already destroyed.");
                continue;
            }
            return choice - 2;
        }
    }

    /** Each player action returns true if it used up the turn. */
    static boolean playerAttack(Player p, Fighter[] enemies) {
        Move[] options = new Move[p.weapon.moves.length + 1 + SKILLS.length];
        int[] skillOf = new int[options.length]; // index into SKILLS, or -1 for weapon moves / Charge
        int n = 0;
        for (int i = 0; i < p.weapon.moves.length; i++) {
            options[n] = p.weapon.moves[i];
            skillOf[n++] = -1;
        }
        for (int i = 0; i < SKILLS.length; i++) {
            if (p.knownSkills[i]) {
                options[n] = SKILLS[i];
                skillOf[n++] = i;
            }
        }

        System.out.println("Choose a move (" + p.weapon.name + "):");
        for (int i = 0; i < n; i++) {
            Move m = options[i];
            String text;
            if (m.charge) {
                text = "Charge - your next attack that lands deals double damage";
            } else {
                int accuracy = (int) Math.round(Math.min(1.0, m.hitChance + p.dexBonus()) * 100);
                text = String.format("%s [%s] (power %d, accuracy %d%%)", m.name,
                        m.element == null ? "NONE" : m.element.toString(), m.power, accuracy);
                if (skillOf[i] < 0 && m.magic) text += " - magic attack (Intelligence)";
                if (skillOf[i] >= 0) text += String.format(" - %s skill, %d/%d uses left",
                        m.magic ? "magic" : "physical", p.skillUses[skillOf[i]], m.uses);
            }
            System.out.printf("  [%d] %s%n", i + 1, text);
        }
        System.out.println("  [0] Back");

        int choice = readInt(0, n);
        if (choice == 0) return false;

        Move move = options[choice - 1];
        int skill = skillOf[choice - 1];

        if (move.charge) {
            if (p.charged) {
                System.out.println("You are already charged!");
                return false;
            }
            performAttack(p, null, move, null);
            return true;
        }
        if (skill >= 0 && p.skillUses[skill] <= 0) {
            System.out.println("You have no uses of " + move.name + " left this battle.");
            return false;
        }

        Fighter target = chooseTarget(enemies);
        if (target == null) return false;
        BodyPart part = null;
        if (target instanceof Boss) {
            int idx = choosePart((Boss) target);
            if (idx == -2) return false;
            if (idx >= 0) part = ((Boss) target).parts[idx];
        }

        if (skill >= 0) p.skillUses[skill]--;
        performAttack(p, target, move, part);
        return true;
    }

    static boolean playerGuard(Player p) {
        p.guarding = true;
        System.out.println(p.name + " takes a defensive stance. Incoming damage is halved until your next turn.");
        return true;
    }

    /** Like Guard, but triples evasion instead of halving damage. */
    static boolean playerDodge(Player p) {
        double before = p.evasion();
        p.dodging = true;
        System.out.printf("%s watches for openings and prepares to dodge! Evasion x%d (%d%% -> %d%%) until your next turn.%n",
                p.name, (int) DODGE_EVASION_MULT, Math.round(before * 100), Math.round(p.evasion() * 100));
        return true;
    }

    static String cooldownNote(Player p, Item it) {
        if (it.type == ItemType.SMOKE) {
            if (p.smokeTurns > 0) return " [active]";
            if (p.smokeCooldown > 0) return " [cooldown " + p.smokeCooldown + "]";
        }
        if (it.type == ItemType.FLASH) {
            if (p.flashTimer > 0) return " [active]";
            if (p.flashCooldown > 0) return " [cooldown " + p.flashCooldown + "]";
        }
        return "";
    }

    static void cureBleeding(Fighter f) {
        if (f.bleeding) {
            f.bleeding = false;
            f.bleedDamage = BLEED_START;
            System.out.println("  The bleeding stops.");
        }
    }

    static boolean playerItem(Player p, Fighter[] enemies) {
        Inventory inv = p.inventory;
        if (inv.itemCount == 0) {
            System.out.println("Your inventory is empty!");
            return false;
        }
        System.out.println("Choose an item:");
        for (int i = 0; i < inv.itemCount; i++) {
            Item it = inv.items[i];
            String info = it.isBook() ? "study outside of battle" : describeItem(it, p.potency()) + cooldownNote(p, it);
            System.out.printf("  [%d] %s x%d (%s)%n", i + 1, it.name, inv.counts[i], info);
        }
        System.out.println("  [0] Back");

        int choice = readInt(0, inv.itemCount);
        if (choice == 0) return false;

        int slot = choice - 1;
        Item item = inv.items[slot];
        int amount = item.value + p.potency();

        if (item.isBook()) {
            System.out.println("You can't study in the middle of a battle!");
            return false;
        }

        switch (item.type) {
            case HEAL:
                inv.removeOne(slot);
                p.heal(amount);
                System.out.printf("%s uses %s and recovers %d HP. (%d/%d HP)%n", p.name, item.name, amount, p.hp, p.maxHp);
                cureBleeding(p);
                return true;

            case MAXHEAL:
                if (p.hp == p.maxHp) {
                    System.out.println("You are already at full HP.");
                    return false;
                }
                inv.removeOne(slot);
                p.hp = p.maxHp;
                System.out.printf("%s drinks the %s and is restored to full health! (%d/%d HP)%n", p.name, item.name, p.hp, p.maxHp);
                cureBleeding(p);
                return true;

            case ANTIDOTE:
                if (!p.poisoned) {
                    System.out.println("You aren't poisoned.");
                    return false;
                }
                inv.removeOne(slot);
                p.poisoned = false;
                System.out.println(p.name + " drinks the antidote. The poison is gone!");
                return true;

            case WATER:
                if (!p.burning) {
                    System.out.println("You aren't burning.");
                    return false;
                }
                inv.removeOne(slot);
                p.burning = false;
                System.out.println(p.name + " douses the flames with water!");
                return true;

            case WARM:
                if (!p.frostbitten && !p.shocked) {
                    System.out.println("You aren't frostbitten or shocked.");
                    return false;
                }
                inv.removeOne(slot);
                p.frostbitten = false;
                p.shocked = false;
                System.out.println(p.name + " sips the warm drink. The frostbite and static fade!");
                return true;

            case SMOKE:
                if (p.smokeTurns > 0 || p.smokeCooldown > 0) {
                    System.out.println("You can't use a Smoke Bomb yet. " + cooldownNote(p, item).trim());
                    return false;
                }
                inv.removeOne(slot);
                p.smokeTurns = SMOKE_TURNS;
                System.out.printf("%s throws a Smoke Bomb! Every attack on you will miss for %d turns.%n", p.name, SMOKE_TURNS);
                return true;

            case FLASH: {
                if (p.flashTimer > 0 || p.flashCooldown > 0) {
                    System.out.println("You can't use a Flashbomb yet. " + cooldownNote(p, item).trim());
                    return false;
                }
                Fighter target = chooseTarget(enemies);
                if (target == null) return false;
                inv.removeOne(slot);
                target.flashTurns = FLASH_TURNS;
                p.flashTimer = FLASH_TURNS;
                System.out.printf("%s hurls a Flashbomb! %s is blinded: accuracy -30%% for %d turns.%n", p.name, target.name, FLASH_TURNS);
                return true;
            }

            case TRAP: {
                Fighter target = chooseTarget(enemies);
                if (target == null) return false;
                if (target.shockTrap) {
                    System.out.println("There is already a Shock Trap set for " + target.name + ".");
                    return false;
                }
                inv.removeOne(slot);
                target.shockTrap = true;
                System.out.printf("%s sets a Shock Trap for %s. If it attacks in melee, it will be caught!%n", p.name, target.name);
                return true;
            }

            default: {
                // Thrown damage item: flat damage to the chosen enemy (a boss's main HP), and maybe a status
                Fighter target = chooseTarget(enemies);
                if (target == null) return false;
                inv.removeOne(slot);
                target.takeDamage(amount);
                System.out.printf("%s throws %s! %s takes %d damage. (%d/%d HP)%n", p.name, item.name, target.name, amount, target.hp, target.maxHp);
                if (item.status != null && target.isAlive() && RNG.nextDouble() < item.statusChance) {
                    applyStatus(target, item.status);
                }
                return true;
            }
        }
    }

    static boolean playerPosition(Player p) {
        Position next = p.position.opposite();
        System.out.printf("Move to %s? (%s)%n  [1] Yes  [0] Back%n", next, next.description);
        if (readInt(0, 1) == 0) return false;

        p.position = next;
        System.out.println(p.name + " moves to the " + next + " line. (Repositioning is a free action - you may still act this turn.)");
        return false; // free action: the turn is not used up
    }

    static boolean tryRetreat(Player p) {
        double chance = Math.max(0.10, Math.min(0.95, 0.50 + p.position.retreatBonus + p.dexBonus()));
        System.out.printf("%s tries to retreat... (%d%% chance)%n", p.name, (int) Math.round(chance * 100));
        boolean escaped = RNG.nextDouble() < chance;
        if (!escaped) System.out.println("You couldn't get away!");
        return escaped;
    }

    // ---------------- Attacks ----------------

    /**
     * Crit check using the required trigger condition.
     * The crit zone is [critChance, 1], so critChance is the threshold:
     * a 1/100 crit rate means a threshold of 0.99 (rolls of 0.99 - 1.0 crit).
     */
    static boolean rollCrit(Fighter attacker) {
        double critRoll = RNG.nextDouble();                  // 0.0 <= roll < 1.0
        double critChance = 1.0 - attacker.critRate();       // 0.99 at base
        if (critRoll >= critChance && critRoll <= 1) {
            return true;  // critical hit
        } else {
            return false; // normal hit
        }
    }

    static void performAttack(Fighter attacker, Fighter defender, Move move) {
        performAttack(attacker, defender, move, null);
    }

    /** part is only used when the defender is the Cockatrice and a specific body part was targeted. */
    static void performAttack(Fighter attacker, Fighter defender, Move move, BodyPart part) {
        if (move.charge) {
            attacker.charged = true;
            System.out.printf("%s gathers power! The next attack that lands deals double damage.%n", attacker.name);
            return;
        }

        // Shock Trap: triggers when its target tries a melee attack, cancelling that attack
        if (attacker.shockTrap && isMelee(attacker, move)) {
            attacker.shockTrap = false;
            System.out.printf("%s charges in with %s... and trips the SHOCK TRAP! It can't move this turn!%n", attacker.name, move.name);
            if (RNG.nextInt(100) < 30) applyStatus(attacker, Status.STATIC);
            return;
        }

        if (part != null) {
            System.out.printf("%s uses %s on the %s!%n", attacker.name, move.name, part.name);
        } else {
            System.out.printf("%s uses %s!%n", attacker.name, move.name);
        }

        int hits = move.hitsMin + RNG.nextInt(move.hitsMax - move.hitsMin + 1);
        boolean multi = hits > 1;
        if (multi) System.out.printf("  (%d strikes)%n", hits);

        for (int hit = 1; hit <= hits && defender.isAlive(); hit++) {
            strike(attacker, defender, move, part, hit, multi);
        }
    }

    /** Melee = a non-magical, non-elemental attack made with a melee weapon. */
    static boolean isMelee(Fighter attacker, Move move) {
        if (move.charge || move.power <= 0 || move.magic) return false;
        if (move.element == Element.FIRE || move.element == Element.ICE || move.element == Element.LIGHTNING) return false;
        return attacker.weapon == null || !attacker.weapon.ranged;
    }

    static void strike(Fighter attacker, Fighter defender, Move move, BodyPart part, int hit, boolean multi) {
        String tag = multi ? "  Strike " + hit + ": " : "  ";

        if (defender.smokeTurns > 0) {
            System.out.println(tag + "the attack is lost in the smoke!");
            return;
        }

        double hitChance = attacker.accuracyOf(move, defender) + attacker.dexBonus() - defender.evasion();
        if (attacker.flashTurns > 0) hitChance -= FLASH_ACCURACY_CUT; // blinded by a Flashbomb
        hitChance = Math.max(0.05, Math.min(1.0, hitChance));
        if (RNG.nextDouble() > hitChance) {
            System.out.println(tag + (multi ? "missed!" : "...but it missed!"));
            return;
        }

        // "gg ez": flat 999 damage that ignores defense, resistances and every other modifier
        if (move == GG_EZ) {
            defender.takeDamage(GG_EZ.power);
            System.out.printf("%s%s takes %d damage. (%d/%d HP)%n", tag, defender.name, GG_EZ.power, defender.hp, defender.maxHp);
            if (defender instanceof Boss && part != null) ((Boss) defender).onPartHit(part, move, GG_EZ.power);
            return;
        }

        // 1. Base damage: power + roll + 2 per point of TOTAL Strength (Intelligence for magic)
        double damage = move.power + RNG.nextInt(4) + 2 * attacker.totalStat(move.magic ? INT : STR);
        Element el = attacker.elementOf(move); // may differ from move.element (Thunder Cloak, arena effects)

        // 2. Multipliers
        // Ranged weapons (bow, staff) are not hurt by the Back position's damage-dealt penalty.
        double dealt = attacker.position.damageDealtMult;
        if (dealt < 1.0 && attacker.weapon != null && attacker.weapon.ranged) dealt = 1.0;
        damage *= dealt * defender.position.damageTakenMult;
        damage *= attacker.outgoingMult(move, hit); // boss body-part effects and fire output
        damage *= defender.damageTakenMult();       // e.g. an enraged Frostfang Phantom takes 10% more
        if (attacker.infused) damage *= 1.10;
        if (arenaEffect != null && el == arenaEffect) damage *= 1.20; // arena effect: boosts this element for everyone

        if (attacker.charged) {
            attacker.charged = false;
            damage *= 2;
            System.out.println("  The stored power is unleashed! (x2)");
        }
        if (rollCrit(attacker)) {
            damage *= 2;
            System.out.println("  CRITICAL HIT!");
        }
        if (el != null) {
            if (part != null && part.isWeakTo(el)) {
                damage *= 1.5;
                part.known = true;
                System.out.println("  The " + part.name + " is weak to " + el + "! (+50%)");
            } else if (part == null && el == defender.weakness) {
                damage *= 1.5;
                defender.weaknessKnown = true;
                System.out.println("  " + defender.name + " is weak to " + el + "! (+50%)");
            } else if (el == defender.activeResistance()) {
                damage *= 0.5;
                defender.resistanceKnown = true;
                System.out.println("  " + defender.name + " resists " + el + "! (-50%)");
            }
        }
        if (defender.guarding) {
            damage *= 0.5;
            System.out.println("  " + defender.name + " blocked part of the damage!");
        }

        // 3. Flat reductions, applied after damage is calculated
        int defense = Math.max(0, defender.defense() - attacker.armorPen());   // 1 defense ignored per Armor Pen
        int conDeduction = (int) Math.round(1.5 * defender.totalStat(CON));    // 1.5 per point of TOTAL Con
        int reduction = defender.damageReduction();                            // capped at 10
        damage -= defense + conDeduction + reduction;
        int finalDamage = Math.max(1, (int) Math.round(damage));

        // Flaming gel (boss): extra fire damage, weakened or disabled by the throat's condition
        int fireBonus = 0;
        if (attacker.infused && attacker.fireMult() > 0) {
            fireBonus = (int) Math.round(6 * attacker.fireMult());
            if (defender.activeResistance() == Element.FIRE) fireBonus /= 2;
            finalDamage += fireBonus;
        }

        defender.takeDamage(finalDamage);
        if (!multi) {
            System.out.printf("  (Defense -%d, Constitution -%d, Damage Reduction -%d)%n", defense, conDeduction, reduction);
        }
        if (fireBonus > 0) System.out.println("  The flaming gel adds " + fireBonus + " fire damage!");
        System.out.printf("%s%s takes %d damage. (%d/%d HP)%n", tag, defender.name, finalDamage, defender.hp, defender.maxHp);

        // Damage to a boss body part also drains that part's own HP
        if (defender instanceof Boss && part != null) {
            ((Boss) defender).onPartHit(part, move, finalDamage);
        }

        if (defender.isAlive()) {
            if (isMelee(attacker, move)) defender.onMeleeHitBy(attacker, move);
            Status arenaStatus = arenaEffect == null ? null : statusFor(arenaEffect);
            Status[] all = Status.values();
            for (int i = 0; i < all.length; i++) {
                double chance = attacker.statusChanceOf(move, all[i]);
                if (all[i] == arenaStatus && el == arenaEffect) chance += 0.10; // arena effect: +10% status chance
                if (chance > 0 && RNG.nextDouble() < chance) {
                    applyStatus(defender, all[i]);
                }
            }
            if (attacker.infused && attacker.fireMult() > 0 && RNG.nextDouble() < 0.20) {
                applyStatus(defender, Status.BURN);
            }
        }
    }

    // ---------------- Enemy turns ----------------

    static void enemyTurn(Fighter e, Fighter p) {
        e.guarding = false; // guard lasts until the guarder's next turn
        System.out.println("\n--- " + e.name + "'s turn ---");
        startTurn(e);
        if (!e.isAlive()) return;
        if (e.stunned) {
            e.stunned = false;
            System.out.println(e.name + " can't act this turn!");
            return;
        }

        int roll = RNG.nextInt(100);
        if (roll < 65) {
            if (!e.charged && RNG.nextInt(100) < 20) {
                performAttack(e, p, CHARGE);
            } else {
                performAttack(e, p, e.weapon.moves[RNG.nextInt(e.weapon.moves.length)]);
            }
        } else if (roll < 80) {
            e.guarding = true;
            System.out.println(e.name + " braces for impact.");
        } else if (roll < 95) {
            e.position = e.position.opposite();
            System.out.println(e.name + " shifts to the " + e.position + " line.");
        } else {
            System.out.println(e.name + " hesitates.");
        }
    }

    static void bossTurn(Boss b, Fighter p) {
        b.guarding = false;
        System.out.println("\n--- " + b.name + "'s turn ---");
        startTurn(b);
        if (!b.isAlive()) return;
        if (b.stunned) {
            b.stunned = false;
            System.out.println(b.name + " can't act this turn!");
            return;
        }
        b.takeTurn(p);
    }

    // ==================================================================
    // Main menu
    // ==================================================================
    public static void main(String[] args) {
        System.out.println(LINE);
        System.out.println(" welcome to a generic rpg game but on cmd ");
        System.out.println(" FINAL BOSS MODE: every stat is 100 and you have one move: gg ez ");

        Player current = null; // kept in memory while the program is open
        boolean running = true;
        while (running) {
            say(LINE,
                "                MAIN MENU                 ",
                LINE);
            if (current != null) {
                System.out.printf("Current character: %s (HP %d/%d, %d gold)%n", current.name, current.hp, current.maxHp, current.gold);
                System.out.println(LINE);
            }
            say("OPTIONS",
                "[1] NEW GAME",
                "[2] CONTINUE",
                "[3] GAMEPLAY MECHANICS",
                "[4] CREDITS",
                "[0] QUIT");
            System.out.println();
            System.out.println("Please select an option:");

            switch (readInt(0, 4)) {
                case 1:
                    if (current != null) {
                        System.out.println("Starting a new game will replace your current character.");
                        System.out.println("  [1] Continue  [0] Cancel");
                        if (readInt(0, 1) == 0) break;
                    }
                    current = newCharacter();
                    explore(current);                                         // the Calamitatum Rex is waiting
                    if (current.isAlive() && !current.gameComplete) adventure(current); // only if you fled
                    if (!current.isAlive() || current.gameComplete) current = null; // game over / game won clears the character
                    break;
                case 2:
                    if (current == null) {
                        System.out.println("No character in progress.");
                    } else {
                        adventure(current);
                        if (!current.isAlive() || current.gameComplete) current = null;
                    }
                    break;
                case 3:
                    showMechanics();
                    pause();
                    break;
                case 4:
                    showCredits();
                    pause();
                    break;
                case 0:
                    running = false;
                    break;
            }
        }
        SC.close();
    }
}