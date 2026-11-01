import java.util.Random;
import java.util.Scanner;

public class randomizedrpg {

    // ==================================================================
    // Shared tools and constants
    // ==================================================================
    static final Random RNG = new Random();
    static final Scanner SC = new Scanner(System.in);
    static final String LINE = "==========================================";
    static final String AREA_NAME = "Forest";

    static final String[] STAT_NAMES = { "Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Luck" };
    static final int STR = 0, DEX = 1, CON = 2, INT = 3, WIS = 4, LCK = 5;

    static final double BASE_CRIT_RATE = 0.01;   // 1/100 base crit chance
    static final int MAX_DAMAGE_REDUCTION = 10;  // cap on flat damage reduction
    static final int STARTING_GOLD = 50;
    static final int SHOP_SLOTS = 5;
    static final int BASIC_ITEMS = 5;            // first 5 entries in ITEMS: potions, bomb, antidote, water
    static final int BOSS_INTERVAL = 5;          // the boss appears after this many defeated enemies

    // Damage over time
    static final int BLEED_START = 2;            // bleed: 2, 3, 4, ... per turn until cured
    static final int POISON_DAMAGE = 4;
    static final int BURN_DAMAGE = 3;
    static final int BURN_WEAK_BONUS = 5;        // extra burn damage on targets weak to fire
    static final int NATURAL_CURE_CHANCE = 20;   // % chance per turn that a status wears off

    // ==================================================================
    // Elements, statuses, positions
    // ==================================================================

    /** SLASH / PIERCE / BLUNT are physical damage types (Strength); FIRE / ICE / LIGHTNING are magical (Intelligence). */
    enum Element { SLASH, PIERCE, BLUNT, FIRE, ICE, LIGHTNING }

    enum Status { BLEED, POISON, BURN }

    /** Special tags used by the boss to tie moves to its body parts. */
    enum MoveTag { NONE, BEAK, CLAWS, TAIL }

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

        Move(String name, int power, double hitChance, Element element, boolean magic, int uses, boolean charge,
             MoveTag tag, int hitsMin, int hitsMax, Status status, double statusChance) {
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
        final Element type; // primary physical damage type: SLASH, PIERCE or BLUNT
        final int armorPen; // defense ignored per hit
        final int price;
        final Move[] moves;

        Weapon(String name, Element type, int armorPen, int price, Move... moves) {
            this.name = name;
            this.type = type;
            this.armorPen = armorPen;
            this.price = price;
            this.moves = moves;
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
        final int price;
        final int[] bonus;        // stat bonuses, indexed like STAT_NAMES

        Armor(String name, int defense, int damageReduction, Element resist, boolean fireproof, int price, int[] bonus) {
            this.name = name;
            this.defense = defense;
            this.damageReduction = damageReduction;
            this.resist = resist;
            this.fireproof = fireproof;
            this.price = price;
            this.bonus = bonus;
        }

        String info() {
            String text = "DEF " + defense + ", DR " + damageReduction;
            if (resist != null) text += ", resists " + resist;
            if (fireproof) text += ", fireproof";
            for (int i = 0; i < bonus.length; i++) {
                if (bonus[i] != 0) text += String.format(", %+d %s", bonus[i], STAT_NAMES[i].substring(0, 3).toUpperCase());
            }
            return text;
        }
    }

    enum ItemType { HEAL, DAMAGE, ANTIDOTE, WATER, SPELLBOOK, SCROLL }

    static class Item {
        final String name;
        final ItemType type;
        final int value;      // heal amount / damage (unused otherwise)
        final int price;
        final int skillIndex; // books only: index into SKILLS, otherwise -1

        Item(String name, ItemType type, int value, int price) {
            this(name, type, value, price, -1);
        }

        Item(String name, ItemType type, int value, int price, int skillIndex) {
            this.name = name;
            this.type = type;
            this.value = value;
            this.price = price;
            this.skillIndex = skillIndex;
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
            case DAMAGE: return "DAMAGE " + (it.value + bonus);
            case ANTIDOTE: return "cures poison";
            case WATER: return "cures burning";
            default: return "teaches " + SKILLS[it.skillIndex].name;
        }
    }

    // ==================================================================
    // Catalogs
    // ==================================================================

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
                new Move("Thrust", 14, 0.85, Element.PIERCE),
                new Move("Cleave", 20, 0.80, Element.SLASH)),
        new Weapon("Hunter's Bow", Element.PIERCE, 2, 110,
                new Move("Quick Shot", 8, 1.00, Element.PIERCE),
                new Move("Aimed Shot", 16, 0.75, Element.PIERCE),
                new Move("Volley", 22, 0.70, Element.PIERCE)),
        new Weapon("Rusty Dagger", Element.PIERCE, 4, 70,
                new Move("Stab", 7, 1.00, Element.PIERCE),
                new Move("Backstab", 13, 0.85, Element.SLASH)),
        new Weapon("War Axe", Element.SLASH, 3, 130,
                new Move("Chop", 14, 0.85, Element.SLASH),
                new Move("Smash", 24, 0.70, Element.BLUNT)),
        new Weapon("Spiked Mace", Element.BLUNT, 2, 100,
                new Move("Bash", 12, 0.95, Element.BLUNT),
                new Move("Crush", 18, 0.80, Element.BLUNT),
                new Move("Skull Crack", 26, 0.70, Element.BLUNT)),
        new Weapon("Iron Spear", Element.PIERCE, 3, 100,
                new Move("Lunge", 11, 0.95, Element.PIERCE),
                new Move("Haft Strike", 9, 0.95, Element.BLUNT),
                new Move("Impale", 21, 0.75, Element.PIERCE))
    };

    static final Armor[] ARMORS = {
        new Armor("Leather Set", 2, 1, null, false, 60, statBlock(0, 1, 0, 0, 0, 0)),
        new Armor("Chainmail Set", 5, 3, Element.SLASH, false, 140, statBlock(0, 0, 1, 0, 0, 0)),
        new Armor("Ashen Mail", 6, 4, Element.FIRE, true, 200, statBlock(0, 0, 1, 0, 0, 0)),
        new Armor("Knight's Plate", 8, 6, Element.BLUNT, false, 260, statBlock(1, -1, 2, 0, 0, 0)),
        new Armor("Dragonscale Set", 12, 10, Element.FIRE, true, 520, statBlock(2, 0, 2, 1, 0, 0))
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

    static final EnemyType SNAKE = new EnemyType("Snake", 45, statBlock(4, 15, 3, 2, 6, 8), 0, 0, 1,
            Element.BLUNT, null,
            new Weapon("Fangs", Element.PIERCE, 0, 0,
                    new Move("Bite", 7, 0.95, Element.PIERCE, Status.POISON, 0.30),
                    new Move("Fang Strike", 12, 0.75, Element.PIERCE, Status.POISON, 0.50)),
            8, 14, 1, 2, null, 0, 0);

    static final EnemyType GOBLIN = new EnemyType("Goblin", 100, statBlock(6, 12, 5, 5, 6, 12), 2, 0, 1,
            Element.LIGHTNING, null,
            new Weapon("Rusty Knife", Element.PIERCE, 0, 0,
                    new Move("Jab", 8, 0.95, Element.PIERCE),
                    new Move("Wild Swing", 14, 0.65, Element.SLASH)),
            10, 18);

    static final EnemyType SKELETON_ARCHER = new EnemyType("Skeleton Archer", 90, statBlock(7, 10, 8, 4, 6, 9), 1, 0, 3,
            Element.BLUNT, Element.PIERCE,
            new Weapon("Shortbow", Element.PIERCE, 0, 0,
                    new Move("Arrow", 10, 0.90, Element.PIERCE),
                    new Move("Piercing Shot", 17, 0.60, Element.PIERCE)),
            15, 25);

    static final EnemyType GREAT_APE = new EnemyType("Great Ape", 140, statBlock(10, 5, 12, 2, 5, 6), 8, 2, 0,
            Element.PIERCE, Element.BLUNT,
            new Weapon("Ape Fists", Element.BLUNT, 0, 0,
                    new Move("Pound", 14, 0.85, Element.BLUNT),
                    new Move("Slam", 22, 0.70, Element.BLUNT)),
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

    /** Builds the group of enemies for one encounter (snakes come in pairs, the alpha leads raptors). */
    static Fighter[] spawnEncounter(EnemyType t) {
        int main = t.groupMin + RNG.nextInt(t.groupMax - t.groupMin + 1);
        int escorts = t.escort == null ? 0 : t.escortMin + RNG.nextInt(t.escortMax - t.escortMin + 1);
        Fighter[] group = new Fighter[main + escorts];
        for (int i = 0; i < main; i++) {
            group[i] = t.spawn(main > 1 ? String.valueOf((char) ('A' + i)) : "");
        }
        for (int i = 0; i < escorts; i++) {
            group[main + i] = t.escort.spawn(escorts > 1 ? String.valueOf((char) ('A' + i)) : "");
        }
        return group;
    }

    // ==================================================================
    // Inventory (arrays only): stackable consumables, owned weapons, owned armor
    // ==================================================================
    static class Inventory {
        static final int ITEM_SLOTS = 12;
        static final int WEAPON_SLOTS = 10;
        static final int ARMOR_SLOTS = 8;

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
        boolean charged = false;
        boolean stunned = false;       // loses its next turn
        boolean infused = false;       // current attack coated in flaming gel (boss)

        // Damage over time
        boolean bleeding = false;
        int bleedDamage = BLEED_START;
        boolean poisoned = false;
        boolean burning = false;

        // Innate values (used by enemies; players get theirs from gear)
        int defenseBase = 0;
        int damageReductionBase = 0;
        int armorPenBase = 0;
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

        /** TOTAL stat = base score + gear bonuses. */
        int totalStat(int stat) {
            return scores[stat] + (armor == null ? 0 : armor.bonus[stat]);
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

        // Hooks the boss overrides
        double evasion() {
            return 0.0;
        }

        double fireMult() {
            return 1.0;
        }

        double outgoingMult(Move move, int hit) {
            return move.element == Element.FIRE ? fireMult() : 1.0;
        }

        double accuracyOf(Move move, Fighter target) {
            return move.hitChance;
        }

        double statusChanceOf(Move move) {
            return move.statusChance;
        }

        void clearStatuses() {
            bleeding = false;
            bleedDamage = BLEED_START;
            poisoned = false;
            burning = false;
            stunned = false;
            infused = false;
            charged = false;
            guarding = false;
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
        int killsSinceBoss = 0;
        int bossesDefeated = 0;
        final boolean[] knownSkills = new boolean[SKILLS.length];
        final int[] skillUses = new int[SKILLS.length];

        Player(String name, int[] scores, Inventory inventory) {
            super(name, 100, scores, null);
            this.inventory = inventory;
            // Constitution -> HP bonus (none if the modifier is 0 or negative)
            maxHp = 100 + Math.max(0, Math.floorDiv(scores[CON] - 10, 2)) * 5;
            hp = maxHp;
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
    // The Cockatrice (boss)
    // ==================================================================
    static class BodyPart {
        final String name;
        final int maxHp;
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

    static class Boss extends Fighter {
        static final int HEAD = 0, THROAT = 1, RIGHT_LEG = 2, LEFT_LEG = 3, TAIL = 4;

        // Damage to a part also reduces the Cockatrice's main HP.
        final BodyPart[] parts = {
            new BodyPart("Head", 150, Element.BLUNT),
            new BodyPart("Throat", 100, Element.BLUNT, Element.SLASH, Element.ICE),
            new BodyPart("Right Leg", 50, Element.BLUNT),
            new BodyPart("Left Leg", 50, Element.BLUNT),
            new BodyPart("Tail", 100, Element.SLASH)
        };
        boolean enraged = false;
        double iceWeaken = 0.0; // fire damage lost to ice hits on the throat

        Boss() {
            super("Cockatrice", 450, statBlock(5, 8, 6, 2, 6, 10),
                    new Weapon("Cockatrice Body", Element.PIERCE, 0, 0, PIERCE_STRIKE));
            defenseBase = 3;
            damageReductionBase = 1;
            armorPenBase = 2;
            weaknessKnown = true;   // its weak points are its body parts
            resistanceKnown = true;
            goldReward = 250;
        }

        /** Evasion drops 20% when enraged (it attacks more recklessly). */
        @Override
        double evasion() {
            double base = 0.15;
            return enraged ? base * 0.8 : base;
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
        double statusChanceOf(Move move) {
            if (move.tag == MoveTag.TAIL && parts[TAIL].broken()) return 0.0; // no more poison
            return move.statusChance;
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
    }

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
            scores[i] = RNG.nextInt(20) + 1;
        }

        Inventory inv = new Inventory();
        inv.addWeapon(WEAPONS[0]); // Iron Sword
        inv.addArmor(ARMORS[0]);   // Leather Set
        inv.addItem(ITEMS[0], 2);  // 2 Potions
        inv.addItem(ITEMS[2], 1);  // 1 Bomb
        inv.addItem(ITEMS[3], 1);  // 1 Antidote
        inv.addItem(ITEMS[4], 1);  // 1 Water Flask

        Player p = new Player(name, scores, inv);
        p.weapon = WEAPONS[0];
        p.armor = ARMORS[0];
        showCharacter(p);
        return p;
    }

    static void showCharacter(Player p) {
        System.out.println(LINE);
        System.out.println("Character Name: " + p.name);
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
        System.out.printf("Enemies defeated since the last boss: %d/%d   Bosses defeated: %d%n",
                p.killsSinceBoss, BOSS_INTERVAL, p.bossesDefeated);
    }

    static void showMechanics() {
        System.out.println(LINE);
        System.out.println("BASE STATS (modifier = floor((total - 10) / 2)):");
        System.out.println("Strength -> +2 damage per point of TOTAL Strength (physical attacks)");
        System.out.println("Dexterity -> Higher TOTAL Dex acts first; modifier gives +2% hit/retreat chance per point");
        System.out.println("Constitution -> -1.5 damage taken per point of TOTAL Con (rounded);");
        System.out.println("                +5 max HP and +1 HP regen per turn per positive modifier point");
        System.out.println("                (no bonus if the modifier is 0 or negative; poison stops regen)");
        System.out.println("Intelligence -> +2 damage per point for magic; detects weaknesses faster;");
        System.out.println("                +5% skill learn chance per point; makes items stronger");
        System.out.println("Wisdom -> Improves item finds while exploring");
        System.out.println("Luck -> Increases critical chance (1% base, +1% per positive modifier point)");
        System.out.println(LINE);
        System.out.println("DAMAGE ORDER:");
        System.out.println("move power + roll + 2 x stat, then position, Charge (x2), crit (x2),");
        System.out.println("weakness (x1.5) / resistance (x0.5) and guard (x0.5).");
        System.out.println("Then flat reductions: Defense (minus attacker Armor Pen), Con deduction,");
        System.out.println("and Damage Reduction (max " + MAX_DAMAGE_REDUCTION + "). Every hit deals at least 1.");
        System.out.println(LINE);
        System.out.println("DAMAGE TYPES:");
        System.out.println("Weapons deal physical damage (scales with Strength): SLASH, PIERCE or BLUNT.");
        System.out.println("Each weapon has a main type, but individual moves can differ.");
        System.out.println("Magic skills deal FIRE, ICE or LIGHTNING (scales with Intelligence).");
        System.out.println(LINE);
        System.out.println("STATUS EFFECTS (they wear off after battle; 20% chance to fade each turn):");
        System.out.println("Bleed -> -2 HP per turn, +1 more each turn it lasts. Cured by Potions.");
        System.out.println("Poison -> -4 HP per turn and no HP regen. Cured by Antidotes.");
        System.out.println("Burn -> -3 HP per turn, defense -15% (-25% and +5 damage if weak to fire).");
        System.out.println("        Cured by Water Flasks. Fireproof armor is immune.");
        System.out.println(LINE);
        System.out.println("COMBAT:");
        System.out.println("Attack -> Weapon moves, Charge, and learned skills (limited uses per battle)");
        System.out.println("Charge -> No damage; your next attack that lands deals double damage");
        System.out.println("Guard -> Halves the next hit you take");
        System.out.println("Item -> Use a potion, bomb, antidote or water flask");
        System.out.println("Position -> Front hits harder but takes more; Back is safer but weaker");
        System.out.println("Retreat -> Chance to flee the fight");
        System.out.println("Fights can have several enemies; everyone acts in order of Dexterity.");
        System.out.println("Enemies have hidden weaknesses/resistances. Hitting them reveals them,");
        System.out.println("and Intelligence helps you spot them each turn.");
        System.out.println(LINE);
        System.out.println("THE " + AREA_NAME.toUpperCase() + ":");
        System.out.println("Snakes, Goblins, Skeleton Archers, Great Apes and the Raptor Alpha (with raptors).");
        System.out.println("After every " + BOSS_INTERVAL + " enemies you defeat, the Cockatrice appears on your next explore.");
        System.out.println("Attack its body parts: damage to a part also drains its main HP.");
        System.out.println("Breaking parts weakens its attacks (head, throat, legs, tail).");
        System.out.println(LINE);
        System.out.println("EXPLORING:");
        System.out.println("[1] Nothing happens  [2] You find an item  [3] A fight begins  [4] A shop appears");
        System.out.println("Defeating enemies earns gold. Spend it at shops on items, weapons, armor,");
        System.out.println("spellbooks (magic) and scrolls (physical) to learn skills.");
    }

    static void showCredits() {
        System.out.println(LINE);
        System.out.println("==================CREDITS=================");
        System.out.println(LINE);
        System.out.println("Insert Group");
        System.out.println("Insert Name");
        System.out.println("Insert Name");
        System.out.println("Insert Name");
        System.out.println("Insert Name");
    }

    // ==================================================================
    // Adventure hub: explore, inventory, equipment, shop
    // ==================================================================
    static void adventure(Player p) {
        boolean inGame = true;
        while (inGame && p.isAlive()) {
            System.out.println(LINE);
            System.out.printf("ADVENTURE (%s) - %s   HP %d/%d   Gold %d%n", AREA_NAME, p.name, p.hp, p.maxHp, p.gold);
            System.out.printf("Weapon: %s   Armor: %s   Boss in: %d kills%n", p.weapon.name,
                    p.armor == null ? "None" : p.armor.name, Math.max(0, BOSS_INTERVAL - p.killsSinceBoss));
            System.out.println("  [1] Explore  [2] Inventory  [3] Equipment  [4] Character  [0] Main Menu");

            switch (readInt(0, 4)) {
                case 1: explore(p); break;
                case 2: inventoryMenu(p); break;
                case 3: equipmentMenu(p); break;
                case 4: showCharacter(p); pause(); break;
                case 0:
                    System.out.println("Returning to the main menu. Choose CONTINUE to resume.");
                    inGame = false;
                    break;
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

        // The boss ambushes you once enough enemies have fallen.
        if (p.killsSinceBoss >= BOSS_INTERVAL) {
            System.out.println("A deafening screech shakes the trees...");
            System.out.println("The COCKATRICE bursts out of the undergrowth!");
            resolveFight(p, new Fighter[] { new Boss() }, true);
            return;
        }

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
                String[] quiet = {
                    "You wander through the trees, but nothing happens.",
                    "The path is quiet. Only the wind keeps you company.",
                    "You find nothing but old footprints."
                };
                System.out.println(quiet[RNG.nextInt(quiet.length)]);
                break;
            case 2:
                findLoot(p);
                break;
            case 4:
                shop(p);
                break;
            default:
                resolveFight(p, spawnEncounter(FOREST[RNG.nextInt(FOREST.length)]), false);
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
        int kills = 0;
        for (Fighter f : group) {
            if (!f.isAlive()) {
                gold += f.goldReward;
                kills++;
            }
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
        }

        if (boss) {
            if (result == Outcome.WIN) {
                p.killsSinceBoss = 0;
                p.bossesDefeated++;
                System.out.println("The Cockatrice has been slain! The forest falls silent.");
            } else {
                System.out.println("The Cockatrice is still out there. It will find you again...");
            }
        } else {
            p.killsSinceBoss += kills;
            if (kills > 0 && p.killsSinceBoss >= BOSS_INTERVAL) {
                System.out.println("\nSomething enormous screeches in the distance. The Cockatrice is hunting you!");
                System.out.println("It will appear the next time you explore.");
            } else if (kills > 0) {
                System.out.printf("Enemies defeated since the last boss: %d/%d%n", p.killsSinceBoss, BOSS_INTERVAL);
            }
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
            } else if (item.type == ItemType.HEAL) {
                if (p.hp == p.maxHp) {
                    System.out.println("You are already at full HP.");
                } else {
                    int amount = item.value + p.potency();
                    inv.removeOne(choice - 1);
                    p.heal(amount);
                    System.out.printf("You use %s and recover %d HP. (%d/%d HP)%n", item.name, amount, p.hp, p.maxHp);
                }
            } else if (item.type == ItemType.DAMAGE) {
                System.out.println(item.name + " can only be used in battle.");
            } else {
                System.out.println("You have no ailment to cure right now. (Status effects only last during battle.)");
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
            System.out.println(LINE);
            System.out.println("EQUIPMENT");
            System.out.println("Weapons:");
            for (int i = 0; i < inv.weaponCount; i++) {
                Weapon w = inv.weapons[i];
                System.out.printf("  [%d] %s%s  [%s, Armor Pen %d]%n", i + 1, w.name, w == p.weapon ? " (equipped)" : "", w.type, w.armorPen);
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
        if (idx < WEAPONS.length) return WEAPONS[idx].type + " weapon, Armor Pen " + WEAPONS[idx].armorPen + "; " + WEAPONS[idx].moveList();
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
            }
        }

        p.clearStatuses();
        for (Fighter e : enemies) {
            e.clearStatuses();
        }
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

    static void applyStatus(Fighter target, Status status) {
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
                if (target.armor != null && target.armor.fireproof) {
                    System.out.println("  " + target.name + "'s fireproof armor shrugs off the flames.");
                } else {
                    target.burning = true;
                    System.out.println("  " + target.name + " catches fire! Defense is reduced.");
                }
                break;
        }
    }

    static String statusText(Fighter f) {
        String text = "";
        if (f.bleeding) text += " (Bleeding -" + f.bleedDamage + ")";
        if (f.poisoned) text += " (Poisoned)";
        if (f.burning) text += " (Burning)";
        if (f.stunned) text += " (Stunned)";
        return text;
    }

    // ---------------- Player turn ----------------

    /** Runs the player's turn. Returns true if the player escaped. */
    static boolean playerTurn(Player p, Fighter[] enemies) {
        p.guarding = false; // guard lasts until the player's next turn
        System.out.println();
        startTurn(p);
        if (!p.isAlive()) return false;

        printStatus(p, enemies);
        tryDetect(p, enemies);

        boolean turnUsed = false;
        while (!turnUsed) {
            System.out.println("\nYour turn:");
            System.out.println("  [1] Attack  [2] Guard  [3] Item  [4] Position  [5] Retreat");

            switch (readInt(1, 5)) {
                case 1: turnUsed = playerAttack(p, enemies); break;
                case 2: turnUsed = playerGuard(p); break;
                case 3: turnUsed = playerItem(p, enemies); break;
                case 4: turnUsed = playerPosition(p); break;
                case 5:
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
            System.out.println("Your insight reveals the Cockatrice's " + part.name + " is weak to " + part.weakText() + "!");
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
        System.out.printf("%-16s HP %3d/%-3d  [%s]%s%s%s%s%n", f.name, f.hp, f.maxHp, f.position,
                f.guarding ? " (Guarding)" : "", f.charged ? " (Charged)" : "",
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
                    System.out.printf("    %-9s %3d/%-3d  weak: %s%s%n", bp.name, bp.hp, bp.maxHp, bp.weakText(),
                            bp.broken() ? "  [DESTROYED]" : "");
                }
            } else {
                System.out.printf("  Weakness: %s | Resistance: %s%n",
                        affinityText(e.weakness, e.weaknessKnown), affinityText(e.resistance, e.resistanceKnown));
            }
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
        options[n] = CHARGE;
        skillOf[n++] = -1;
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

    static boolean playerItem(Player p, Fighter[] enemies) {
        Inventory inv = p.inventory;
        if (inv.itemCount == 0) {
            System.out.println("Your inventory is empty!");
            return false;
        }
        System.out.println("Choose an item:");
        for (int i = 0; i < inv.itemCount; i++) {
            Item it = inv.items[i];
            String info = it.isBook() ? "study outside of battle" : describeItem(it, p.potency());
            System.out.printf("  [%d] %s x%d (%s)%n", i + 1, it.name, inv.counts[i], info);
        }
        System.out.println("  [0] Back");

        int choice = readInt(0, inv.itemCount);
        if (choice == 0) return false;

        Item item = inv.items[choice - 1];
        int amount = item.value + p.potency();

        if (item.isBook()) {
            System.out.println("You can't study in the middle of a battle!");
            return false;
        }
        if (item.type == ItemType.HEAL) {
            inv.removeOne(choice - 1);
            p.heal(amount);
            System.out.printf("%s uses %s and recovers %d HP. (%d/%d HP)%n", p.name, item.name, amount, p.hp, p.maxHp);
            if (p.bleeding) {
                p.bleeding = false;
                p.bleedDamage = BLEED_START;
                System.out.println("  The bleeding stops.");
            }
            return true;
        }
        if (item.type == ItemType.ANTIDOTE) {
            if (!p.poisoned) {
                System.out.println("You aren't poisoned.");
                return false;
            }
            inv.removeOne(choice - 1);
            p.poisoned = false;
            System.out.println(p.name + " drinks the antidote. The poison is gone!");
            return true;
        }
        if (item.type == ItemType.WATER) {
            if (!p.burning) {
                System.out.println("You aren't burning.");
                return false;
            }
            inv.removeOne(choice - 1);
            p.burning = false;
            System.out.println(p.name + " douses the flames with water!");
            return true;
        }

        // Bomb: flat damage to the chosen enemy (the Cockatrice's main HP if it is the target)
        Fighter target = chooseTarget(enemies);
        if (target == null) return false;
        inv.removeOne(choice - 1);
        target.takeDamage(amount);
        System.out.printf("%s throws %s! %s takes %d damage. (%d/%d HP)%n", p.name, item.name, target.name, amount, target.hp, target.maxHp);
        return true;
    }

    static boolean playerPosition(Player p) {
        Position next = p.position.opposite();
        System.out.printf("Move to %s? (%s)%n  [1] Yes  [0] Back%n", next, next.description);
        if (readInt(0, 1) == 0) return false;

        p.position = next;
        System.out.println(p.name + " moves to the " + next + " line.");
        return true;
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

    static void strike(Fighter attacker, Fighter defender, Move move, BodyPart part, int hit, boolean multi) {
        String tag = multi ? "  Strike " + hit + ": " : "  ";

        double hitChance = attacker.accuracyOf(move, defender) + attacker.dexBonus() - defender.evasion();
        hitChance = Math.max(0.05, Math.min(1.0, hitChance));
        if (RNG.nextDouble() > hitChance) {
            System.out.println(tag + (multi ? "missed!" : "...but it missed!"));
            return;
        }

        // 1. Base damage: power + roll + 2 per point of TOTAL Strength (Intelligence for magic)
        double damage = move.power + RNG.nextInt(4) + 2 * attacker.totalStat(move.magic ? INT : STR);

        // 2. Multipliers
        damage *= attacker.position.damageDealtMult * defender.position.damageTakenMult;
        damage *= attacker.outgoingMult(move, hit); // boss body-part effects and fire output
        if (attacker.infused) damage *= 1.10;

        if (attacker.charged) {
            attacker.charged = false;
            damage *= 2;
            System.out.println("  The stored power is unleashed! (x2)");
        }
        if (rollCrit(attacker)) {
            damage *= 2;
            System.out.println("  CRITICAL HIT!");
        }
        if (move.element != null) {
            if (part != null && part.isWeakTo(move.element)) {
                damage *= 1.5;
                part.known = true;
                System.out.println("  The " + part.name + " is weak to " + move.element + "! (+50%)");
            } else if (part == null && move.element == defender.weakness) {
                damage *= 1.5;
                defender.weaknessKnown = true;
                System.out.println("  " + defender.name + " is weak to " + move.element + "! (+50%)");
            } else if (move.element == defender.activeResistance()) {
                damage *= 0.5;
                defender.resistanceKnown = true;
                System.out.println("  " + defender.name + " resists " + move.element + "! (-50%)");
            }
        }
        if (defender.guarding) {
            damage *= 0.5;
            System.out.println("  " + defender.name + " blocked part of the damage!");
        }

        // 3. Flat reductions, applied after damage is calculated
        int defense = Math.max(0, defender.defense() - attacker.armorPen());   // 1 defense ignored per Armor Pen
        int conDeduction = (int) Math.round(1.2 * defender.totalStat(CON));    // 1.5 per point of TOTAL Con
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
            hitBossPart((Boss) defender, part, move, finalDamage);
        }

        if (defender.isAlive()) {
            double chance = attacker.statusChanceOf(move);
            if (move.status != null && RNG.nextDouble() < chance) {
                applyStatus(defender, move.status);
            }
            if (attacker.infused && attacker.fireMult() > 0 && RNG.nextDouble() < 0.20) {
                applyStatus(defender, Status.BURN);
            }
        }
    }

    static void hitBossPart(Boss boss, BodyPart part, Move move, int damage) {
        boolean wasBroken = part.broken();
        part.hp = Math.max(0, part.hp - damage);
        System.out.printf("  The %s takes %d damage. (%d/%d HP)%n", part.name, damage, part.hp, part.maxHp);

        if (!wasBroken && part.broken()) {
            System.out.println("  The Cockatrice's " + part.name.toUpperCase() + " IS DESTROYED!");
            if (part == boss.parts[Boss.HEAD]) System.out.println("  Its beak attacks will deal 40% less damage.");
            if (part == boss.parts[Boss.THROAT]) System.out.println("  It can no longer use fire attacks!");
            if (part == boss.parts[Boss.TAIL]) System.out.println("  Its tail swipes deal 60% less damage and can no longer poison.");
            if (part == boss.parts[Boss.RIGHT_LEG]) System.out.println("  Its right claw strikes (odd swipes) deal 70% less damage.");
            if (part == boss.parts[Boss.LEFT_LEG]) System.out.println("  Its left claw strikes (even swipes) deal 70% less damage.");
            if (boss.parts[Boss.RIGHT_LEG].broken() && boss.parts[Boss.LEFT_LEG].broken()) {
                System.out.println("  With both legs destroyed, it can no longer pounce!");
            }
        }

        // Head: blunt hits have a 20% chance to stun
        if (part == boss.parts[Boss.HEAD] && move.element == Element.BLUNT && !part.broken()
                && !boss.stunned && RNG.nextInt(100) < 20) {
            boss.stunned = true;
            System.out.println("  The blow rattles the Cockatrice's skull - it is STUNNED!");
        }
        // Throat: ice hits further weaken its fire (down to 50%)
        if (part == boss.parts[Boss.THROAT] && move.element == Element.ICE && !part.broken()) {
            boss.iceWeaken += 0.10;
            System.out.printf("  The cold weakens its fire. (fire output now %d%%)%n", (int) Math.round(boss.fireMult() * 100));
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
            System.out.println(e.name + " is stunned and can't act!");
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
            System.out.println("The Cockatrice is stunned and can't act!");
            return;
        }

        if (!b.enraged && b.hp * 2 <= b.maxHp) {
            b.enraged = true;
            System.out.println("The Cockatrice becomes ENRAGED! It attacks recklessly (less evasion),");
            System.out.println("and its attacks may be infused with flaming gel!");
        }

        Move move = b.pickMove();
        b.infused = b.enraged && !move.charge && RNG.nextInt(100) < 35;
        if (b.infused) System.out.println("Flaming gel drips from the Cockatrice's attack!");
        performAttack(b, p, move, null);
        b.infused = false;
    }

    // ==================================================================
    // Main menu
    // ==================================================================
    public static void main(String[] args) {
        System.out.println(LINE);
        System.out.println(" welcome to a generic rpg game but on cmd ");

        Player current = null; // kept in memory while the program is open
        boolean running = true;
        while (running) {
            System.out.println(LINE);
            System.out.println("                MAIN MENU                 ");
            System.out.println(LINE);
            if (current != null) {
                System.out.printf("Current character: %s (HP %d/%d, %d gold)%n", current.name, current.hp, current.maxHp, current.gold);
                System.out.println(LINE);
            }
            System.out.println("OPTIONS");
            System.out.println("[1] NEW GAME");
            System.out.println("[2] CONTINUE");
            System.out.println("[3] GAMEPLAY MECHANICS");
            System.out.println("[4] CREDITS");
            System.out.println("[0] QUIT");
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
                    adventure(current);
                    if (!current.isAlive()) current = null; // game over clears the character
                    break;
                case 2:
                    if (current == null) {
                        System.out.println("No character in progress.");
                    } else {
                        adventure(current);
                        if (!current.isAlive()) current = null;
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
