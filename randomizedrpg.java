import java.util.Random;
import java.util.Scanner;

public class randomizedrpg {

    // ==================================================================
    // Shared tools and constants
    // ==================================================================
    static final Random RNG = new Random();
    static final Scanner SC = new Scanner(System.in);
    static final String LINE = "==========================================";

    static final String[] STAT_NAMES = { "Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Luck" };
    static final int STR = 0, DEX = 1, CON = 2, INT = 3, WIS = 4, LCK = 5;
    static final double BASE_CRIT_RATE = 0.01; // 1/100 base crit chance

    // ==================================================================
    // Position: FRONT = high risk / high reward, BACK = safer but weaker
    // ==================================================================
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
    // Weapons, moves, items
    // ==================================================================
    static class Move {
        final String name;
        final int power;
        final double hitChance; // 0.0 - 1.0

        Move(String name, int power, double hitChance) {
            this.name = name;
            this.power = power;
            this.hitChance = hitChance;
        }
    }

    static class Weapon {
        final String name;
        final Move[] moves;

        Weapon(String name, Move... moves) {
            this.name = name;
            this.moves = moves;
        }

        String moveList() {
            String text = "";
            for (int i = 0; i < moves.length; i++) {
                text += (i > 0 ? ", " : "") + moves[i].name + " (" + moves[i].power + ")";
            }
            return text;
        }
    }

    enum ItemType { HEAL, DAMAGE }

    static class Item {
        final String name;
        final ItemType type;
        final int value;

        Item(String name, ItemType type, int value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }
    }

    // Catalogs: every item/weapon in the game lives here.
    static final Weapon[] WEAPONS = {
        new Weapon("Iron Sword", new Move("Slash", 10, 0.95), new Move("Thrust", 14, 0.80), new Move("Cleave", 20, 0.60)),
        new Weapon("Hunter's Bow", new Move("Quick Shot", 8, 1.00), new Move("Aimed Shot", 16, 0.75), new Move("Volley", 22, 0.55)),
        new Weapon("Rusty Dagger", new Move("Stab", 7, 1.00), new Move("Backstab", 13, 0.85)),
        new Weapon("War Axe", new Move("Chop", 14, 0.85), new Move("Smash", 24, 0.55))
    };

    static final Item[] ITEMS = {
        new Item("Potion", ItemType.HEAL, 30),
        new Item("Hi-Potion", ItemType.HEAL, 60),
        new Item("Bomb", ItemType.DAMAGE, 25)
    };

    // Enemy templates (parallel arrays: name, HP, weapon)
    static final String[] ENEMY_NAMES = { "Dire Wolf", "Goblin", "Skeleton Archer" };
    static final int[] ENEMY_HP = { 90, 70, 60 };
    static final Weapon[] ENEMY_WEAPONS = {
        new Weapon("Feral Claws", new Move("Scratch", 9, 0.95), new Move("Pounce", 15, 0.70)),
        new Weapon("Rusty Knife", new Move("Jab", 8, 0.95), new Move("Wild Swing", 14, 0.65)),
        new Weapon("Shortbow", new Move("Arrow", 10, 0.90), new Move("Piercing Shot", 17, 0.60))
    };

    // ==================================================================
    // Inventory (arrays only): stackable consumables + owned weapons
    // ==================================================================
    static class Inventory {
        static final int ITEM_SLOTS = 10;
        static final int WEAPON_SLOTS = 10;

        final Item[] items = new Item[ITEM_SLOTS];
        final int[] counts = new int[ITEM_SLOTS];
        final Weapon[] weapons = new Weapon[WEAPON_SLOTS];
        int itemCount = 0;
        int weaponCount = 0;

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
    }

    // ==================================================================
    // Fighters
    // ==================================================================
    static class Fighter {
        final String name;
        int maxHp;
        int hp;
        Weapon weapon;
        Position position = Position.FRONT;
        boolean guarding = false;
        int damageBonus = 0;          // flat damage added to every attack (Strength)
        double dexBonus = 0.0;        // added to hit chance and retreat chance (Dexterity)
        double critRate = BASE_CRIT_RATE;

        Fighter(String name, int maxHp, Weapon weapon) {
            this.name = name;
            this.maxHp = maxHp;
            this.hp = maxHp;
            this.weapon = weapon;
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
        final int[] scores;
        final Inventory inventory;

        Player(String name, int[] scores, Inventory inventory) {
            super(name, 100, null);
            this.scores = scores;
            this.inventory = inventory;
            maxHp = 100 + mod(CON) * 5;                          // Constitution -> HP
            hp = maxHp;
            damageBonus = mod(STR);                              // Strength -> damage
            dexBonus = mod(DEX) * 0.02;                          // Dexterity -> accuracy / retreat
            critRate = BASE_CRIT_RATE + Math.max(0, mod(LCK)) * 0.01; // Luck -> crit chance
        }

        int mod(int stat) {
            return Math.floorDiv(scores[stat] - 10, 2);
        }

        /** Intelligence makes items stronger. */
        int potency() {
            return Math.max(0, mod(INT)) * 2;
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
        inv.addWeapon(WEAPONS[0]);
        inv.addItem(ITEMS[0], 2); // 2 Potions
        inv.addItem(ITEMS[2], 1); // 1 Bomb

        Player p = new Player(name, scores, inv);
        p.weapon = WEAPONS[0];
        showCharacter(p);
        return p;
    }

    static void showCharacter(Player p) {
        System.out.println(LINE);
        System.out.println("Character Name: " + p.name);
        System.out.println("HP: " + p.hp + "/" + p.maxHp + "    Weapon: " + p.weapon.name);
        System.out.println(LINE);
        for (int i = 0; i < STAT_NAMES.length; i++) {
            int mod = p.mod(i);
            System.out.println(STAT_NAMES[i] + ": " + p.scores[i] + " (" + (mod > 0 ? "+" : "") + mod + ")");
        }
        System.out.printf("Crit chance: %.0f%%%n", p.critRate * 100);
    }

    static void showMechanics() {
        System.out.println(LINE);
        System.out.println("BASE STATS (modifier = floor((score - 10) / 2)):");
        System.out.println("Strength -> Measures physical power; modifier is added to attack damage");
        System.out.println("Dexterity -> Measures agility; each modifier point gives +2% hit and retreat chance");
        System.out.println("Constitution -> Measures Endurance; max HP = 100 + modifier x 5");
        System.out.println("Intelligence -> Measures Reasoning and Memory; makes items stronger");
        System.out.println("Wisdom -> Measures Perception and Insight; improves item finds while exploring");
        System.out.println("Luck -> Increases critical chance (1% base, +1% per positive modifier point)");
        System.out.println(LINE);
        System.out.println("OTHER STATS:");
        System.out.println("Critical Damage -> Critical hits deal double damage");
        System.out.println("Defense Penetration -> Not implemented yet");
        System.out.println(LINE);
        System.out.println("EXPLORING:");
        System.out.println("[1] Nothing happens  [2] You find an item  [3] A fight begins");
        System.out.println(LINE);
        System.out.println("COMBAT:");
        System.out.println("Attack -> Moves depend on your equipped weapon");
        System.out.println("Guard -> Halves the next hit you take");
        System.out.println("Item -> Use a consumable from your inventory");
        System.out.println("Position -> Front hits harder but takes more; Back is safer but weaker");
        System.out.println("Retreat -> Chance to flee the fight");
    }

    static void showCredits() {
        System.out.println(LINE);
        System.out.println("                  CREDITS                 ");
        System.out.println(LINE);
        System.out.println("BSIT-1B Group 8");
        System.out.println("Dhereck Kharl");
        System.out.println("Andrei Thomas Lumaking");
        System.out.println("James V. Tiglao");
        System.out.println("Paulene Nicole Yumul");
        System.out.println(LINE);
    }

    // ==================================================================
    // Adventure hub: explore, inventory, equipment
    // ==================================================================
    static void adventure(Player p) {
        boolean inGame = true;
        while (inGame && p.isAlive()) {
            System.out.println(LINE);
            System.out.printf("ADVENTURE - %s   HP %d/%d   Weapon: %s%n", p.name, p.hp, p.maxHp, p.weapon.name);
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

    /** Event generator: 1 = nothing happens, 2 = item found, 3 = fight. */
    static void explore(Player p) {
        int fightChance = 35;
        int itemChance = Math.max(20, Math.min(40, 30 + p.mod(WIS) * 2)); // Wisdom helps find items
        int roll = RNG.nextInt(100);
        int event = roll < fightChance ? 3 : (roll < fightChance + itemChance ? 2 : 1);

        System.out.println(LINE);
        switch (event) {
            case 1:
                String[] quiet = {
                    "You wander for a while, but nothing happens.",
                    "The path is quiet. Only the wind keeps you company.",
                    "You find nothing but old footprints."
                };
                System.out.println(quiet[RNG.nextInt(quiet.length)]);
                break;
            case 2:
                findLoot(p);
                break;
            default:
                Fighter enemy = randomEnemy();
                Outcome result = fight(p, enemy);
                if (result == Outcome.WIN) {
                    int recovered = Math.max(1, p.maxHp * 15 / 100);
                    p.heal(recovered);
                    System.out.println("\nVictory! " + enemy.name + " was defeated.");
                    System.out.println("You catch your breath and recover " + recovered + " HP.");
                } else if (result == Outcome.FLED) {
                    System.out.println("\nYou escaped successfully!");
                } else {
                    System.out.println("\nDefeat... " + p.name + " has fallen. GAME OVER.");
                    pause();
                }
                break;
        }
    }

    static void findLoot(Player p) {
        Inventory inv = p.inventory;
        if (RNG.nextInt(100) < 70) {
            Item item = ITEMS[RNG.nextInt(ITEMS.length)];
            if (inv.addItem(item, 1)) {
                System.out.println("You found a " + item.name + "! It was added to your inventory.");
            } else {
                System.out.println("You found a " + item.name + ", but your inventory is full.");
            }
        } else {
            Weapon w = WEAPONS[RNG.nextInt(WEAPONS.length)];
            if (inv.hasWeapon(w)) {
                System.out.println("You found a " + w.name + ", but you already own one.");
            } else if (inv.addWeapon(w)) {
                System.out.println("You found a " + w.name + "! Equip it from the Equipment menu.");
            } else {
                System.out.println("You found a " + w.name + ", but you can't carry any more weapons.");
            }
        }
    }

    static Fighter randomEnemy() {
        int i = RNG.nextInt(ENEMY_NAMES.length);
        return new Fighter(ENEMY_NAMES[i], ENEMY_HP[i], ENEMY_WEAPONS[i]);
    }

    static void inventoryMenu(Player p) {
        Inventory inv = p.inventory;
        while (true) {
            System.out.println(LINE);
            System.out.println("INVENTORY   (weapons owned: " + inv.weaponCount + "/" + Inventory.WEAPON_SLOTS + ")");
            if (inv.itemCount == 0) {
                System.out.println("Your consumable pouch is empty.");
                return;
            }
            for (int i = 0; i < inv.itemCount; i++) {
                Item it = inv.items[i];
                System.out.printf("  [%d] %s x%d (%s %d)%n", i + 1, it.name, inv.counts[i], it.type, it.value + p.potency());
            }
            System.out.println("  [0] Back");

            int choice = readInt(0, inv.itemCount);
            if (choice == 0) return;

            Item item = inv.items[choice - 1];
            if (item.type != ItemType.HEAL) {
                System.out.println(item.name + " can only be used in battle.");
            } else if (p.hp == p.maxHp) {
                System.out.println("You are already at full HP.");
            } else {
                int amount = item.value + p.potency();
                inv.removeOne(choice - 1);
                p.heal(amount);
                System.out.printf("You use %s and recover %d HP. (%d/%d HP)%n", item.name, amount, p.hp, p.maxHp);
            }
        }
    }

    static void equipmentMenu(Player p) {
        Inventory inv = p.inventory;
        while (true) {
            System.out.println(LINE);
            System.out.println("EQUIPMENT - Weapon: " + p.weapon.name);
            for (int i = 0; i < inv.weaponCount; i++) {
                Weapon w = inv.weapons[i];
                System.out.printf("  [%d] %s%s%n", i + 1, w.name, w == p.weapon ? " (equipped)" : "");
                System.out.println("      Moves: " + w.moveList());
            }
            System.out.println("  [0] Back");

            int choice = readInt(0, inv.weaponCount);
            if (choice == 0) return;

            p.weapon = inv.weapons[choice - 1];
            System.out.println("You equip the " + p.weapon.name + ".");
        }
    }

    // ==================================================================
    // Combat
    // ==================================================================
    static Outcome fight(Player p, Fighter e) {
        p.position = Position.FRONT;
        e.position = Position.FRONT;
        p.guarding = false;
        e.guarding = false;
        System.out.printf("A wild %s appears!%n", e.name);

        while (p.isAlive() && e.isAlive()) {
            printStatus(p, e);
            p.guarding = false; // guard lasts until the player's next turn

            boolean turnUsed = false;
            while (!turnUsed) {
                System.out.println("\nYour turn:");
                System.out.println("  [1] Attack  [2] Guard  [3] Item  [4] Position  [5] Retreat");

                switch (readInt(1, 5)) {
                    case 1: turnUsed = playerAttack(p, e); break;
                    case 2: turnUsed = playerGuard(p); break;
                    case 3: turnUsed = playerItem(p, e); break;
                    case 4: turnUsed = playerPosition(p); break;
                    case 5:
                        if (tryRetreat(p)) return Outcome.FLED;
                        turnUsed = true;
                        break;
                }
            }

            if (!e.isAlive()) break;
            enemyTurn(e, p);
        }

        printStatus(p, e);
        return p.isAlive() ? Outcome.WIN : Outcome.LOSE;
    }

    /**
     * Crit check using the required trigger condition.
     * The crit zone is [critChance, 1], so critChance is the threshold:
     * a 1/100 crit rate means a threshold of 0.99 (rolls of 0.99 - 1.0 crit).
     */
    static boolean rollCrit(Fighter attacker) {
        double critRoll = RNG.nextDouble();                  // 0.0 <= roll < 1.0
        double critChance = 1.0 - attacker.critRate;         // 0.99 at base
        if (critRoll >= critChance && critRoll <= 1) {
            return true;  // critical hit
        } else {
            return false; // normal hit
        }
    }

    static void performAttack(Fighter attacker, Fighter defender, Move move) {
        System.out.printf("%s uses %s!%n", attacker.name, move.name);

        double hitChance = Math.min(1.0, move.hitChance + attacker.dexBonus);
        if (RNG.nextDouble() > hitChance) {
            System.out.println("  ...but it missed!");
            return;
        }

        double damage = move.power + RNG.nextInt(4) + attacker.damageBonus;
        damage *= attacker.position.damageDealtMult * defender.position.damageTakenMult;

        if (rollCrit(attacker)) {
            damage *= 2;
            System.out.println("  CRITICAL HIT!");
        }
        if (defender.guarding) {
            damage *= 0.5;
            System.out.println("  " + defender.name + " blocked part of the damage!");
        }

        int finalDamage = Math.max(1, (int) Math.round(damage));
        defender.takeDamage(finalDamage);
        System.out.printf("  %s takes %d damage. (%d/%d HP)%n", defender.name, finalDamage, defender.hp, defender.maxHp);
    }

    static void printStatus(Fighter p, Fighter e) {
        System.out.println("\n==============================================");
        System.out.printf("%-14s HP %3d/%-3d  [%s]%s%n", p.name, p.hp, p.maxHp, p.position, p.guarding ? " (Guarding)" : "");
        System.out.printf("%-14s HP %3d/%-3d  [%s]%s%n", e.name, e.hp, e.maxHp, e.position, e.guarding ? " (Guarding)" : "");
        System.out.println("==============================================");
    }

    /** Each player action returns true if it used up the turn. */
    static boolean playerAttack(Player p, Fighter e) {
        System.out.println("Choose a move (" + p.weapon.name + "):");
        Move[] moves = p.weapon.moves;
        for (int i = 0; i < moves.length; i++) {
            Move m = moves[i];
            int accuracy = (int) Math.round(Math.min(1.0, m.hitChance + p.dexBonus) * 100);
            System.out.printf("  [%d] %s (power %d, accuracy %d%%)%n", i + 1, m.name, m.power, accuracy);
        }
        System.out.println("  [0] Back");

        int choice = readInt(0, moves.length);
        if (choice == 0) return false;
        performAttack(p, e, moves[choice - 1]);
        return true;
    }

    static boolean playerGuard(Player p) {
        p.guarding = true;
        System.out.println(p.name + " takes a defensive stance. Incoming damage is halved until your next turn.");
        return true;
    }

    static boolean playerItem(Player p, Fighter e) {
        Inventory inv = p.inventory;
        if (inv.itemCount == 0) {
            System.out.println("Your inventory is empty!");
            return false;
        }
        System.out.println("Choose an item:");
        for (int i = 0; i < inv.itemCount; i++) {
            Item it = inv.items[i];
            System.out.printf("  [%d] %s x%d (%s %d)%n", i + 1, it.name, inv.counts[i], it.type, it.value + p.potency());
        }
        System.out.println("  [0] Back");

        int choice = readInt(0, inv.itemCount);
        if (choice == 0) return false;

        Item item = inv.items[choice - 1];
        int amount = item.value + p.potency();
        inv.removeOne(choice - 1);

        if (item.type == ItemType.HEAL) {
            p.heal(amount);
            System.out.printf("%s uses %s and recovers %d HP. (%d/%d HP)%n", p.name, item.name, amount, p.hp, p.maxHp);
        } else {
            e.takeDamage(amount);
            System.out.printf("%s throws %s! %s takes %d damage. (%d/%d HP)%n", p.name, item.name, e.name, amount, e.hp, e.maxHp);
        }
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
        double chance = Math.max(0.10, Math.min(0.95, 0.50 + p.position.retreatBonus + p.dexBonus));
        System.out.printf("%s tries to retreat... (%d%% chance)%n", p.name, (int) Math.round(chance * 100));
        boolean escaped = RNG.nextDouble() < chance;
        if (!escaped) System.out.println("You couldn't get away!");
        return escaped;
    }

    static void enemyTurn(Fighter e, Fighter p) {
        e.guarding = false; // guard lasts until the guarder's next turn
        System.out.println("\n--- " + e.name + "'s turn ---");

        int roll = RNG.nextInt(100);
        if (roll < 65) {
            performAttack(e, p, e.weapon.moves[RNG.nextInt(e.weapon.moves.length)]);
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
                System.out.printf("Current character: %s (HP %d/%d)%n", current.name, current.hp, current.maxHp);
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