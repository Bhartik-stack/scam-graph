package com.scamgraph.data;

import com.scamgraph.model.Transaction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Creates a realistic but completely FAKE month of bank transactions for a small town.
 *
 * Real bank data is private, so we simulate it:
 *  - Normal life: salaries, rent, shopping at local shops, bills, money to friends and family,
 *    shops paying their suppliers, a college fest committee collecting money.
 *  - A hidden "digital arrest" fraud ring: victims are scared into paying "CBI officers",
 *    the money is collected by mule accounts, split, passed on, sent round in a loop to
 *    confuse investigators, kept just under Rs 50,000 per payment, and finally cashed out.
 *
 * The same seed always produces the same data, so the demo is repeatable.
 * All names are randomly combined and fictional.
 */
public final class SampleDataGenerator {

    private static final String[] FIRST = {"Aarav", "Vivaan", "Aditya", "Arjun", "Rohan", "Kabir", "Ishaan", "Karan",
            "Harpreet", "Gurpreet", "Manpreet", "Jaskaran", "Simran", "Navneet", "Rahul", "Amit", "Sanjay", "Vikram",
            "Neha", "Priya", "Ananya", "Diya", "Kavya", "Meera", "Pooja", "Riya", "Sneha", "Tanvi", "Aisha", "Zoya",
            "Farhan", "Imran", "Sahil", "Nikhil", "Deepak", "Suresh", "Ramesh", "Anjali", "Shalini", "Ritika",
            "Varun", "Yash", "Mohit", "Gaurav", "Tanya", "Komal", "Bhavna", "Parth", "Dev", "Lakshmi"};
    private static final String[] LAST = {"Sharma", "Verma", "Gupta", "Singh", "Kaur", "Gill", "Sandhu", "Dhillon",
            "Mehta", "Malhotra", "Kapoor", "Bhatia", "Arora", "Chopra", "Khanna", "Saini", "Thakur", "Rana", "Jain",
            "Bansal", "Aggarwal", "Sethi", "Wadhwa", "Bedi", "Grewal", "Mann", "Kohli", "Ahuja", "Rao", "Nair"};
    private static final String[] SHOPS = {"Sharma Kirana Store", "Gill Medical Hall", "City Petrol Pump", "Annapurna Dhaba",
            "Bansal Sweets", "Royal Bakery", "Fresh Veggies Mart", "Sai Mobile Point", "Khalsa Hardware", "Raj Dairy",
            "Student Mess Canteen", "Bright Coaching Centre", "Style Salon", "New Fashion Garments", "Jain Stationers",
            "Lucky Chemist", "Green Fruit Corner", "Metro Supermart", "Speed Auto Repair", "Book World",
            "Taste of Punjab", "Corner Tea Stall", "Om Electricals", "Ganesh Flour Mill", "Classic Footwear",
            "Sunrise Gym", "Quick Laundry", "Spice Junction", "Daily Needs Store", "Campus Xerox"};
    private static final String[] EMPLOYERS = {"Shivalik Infotech Pvt Ltd", "Punjab Agro Foods Ltd", "Doaba Textiles",
            "Sutlej Motors", "Green Valley School", "City Hospital Trust", "Northstar Logistics", "Bright Minds Academy",
            "Kisan Cold Storage", "Apex Pharma Distributors"};
    private static final String[] SUPPLIERS = {"Ludhiana Wholesale Traders", "Amritsar Distribution Co",
            "Northern FMCG Agency", "Punjab Pharma Wholesale", "Metro Fuel Depot", "Doaba Textile Mills"};
    private static final String[] CLIENTS = {"State Procurement Board", "National Retail Chain", "Export House Ltd",
            "Regional Health Mission", "Mega Builders"};
    private static final String[] UTILITIES = {"PSPCL Electricity", "JioFiber Broadband", "Municipal Water Board"};

    private final Random rnd;
    private final LocalDate start = LocalDate.of(2026, 10, 1);
    private final int days = 30;
    private final List<Transaction> out = new ArrayList<>();
    private int txnCounter = 1;
    private int accountCounter = 100_001;

    private final List<Account> people = new ArrayList<>();
    private final List<Account> shops = new ArrayList<>();
    private final List<Account> employers = new ArrayList<>();
    private final List<Account> suppliers = new ArrayList<>();
    private final List<Account> clients = new ArrayList<>();
    private final List<Account> landlords = new ArrayList<>();
    private final List<Account> utilities = new ArrayList<>();

    private record Account(String id, String name) {
    }

    public SampleDataGenerator(long seed) {
        this.rnd = new Random(seed);
    }

    /** Builds the dataset. With {@code includeFraud = false} you get a clean town with no scam. */
    public List<Transaction> generate(boolean includeFraud) {
        createAccounts();
        normalLife();
        if (includeFraud) digitalArrestRing();
        out.sort(Comparator.comparing(Transaction::time).thenComparing(Transaction::id));
        // renumber so ids follow time order
        List<Transaction> result = new ArrayList<>(out.size());
        int k = 1;
        for (Transaction t : out) {
            result.add(new Transaction(String.format("TXN%06d", k++), t.from(), t.fromName(), t.to(), t.toName(),
                    t.amount(), t.time(), t.channel()));
        }
        return result;
    }

    // ---------------------------------------------------------------- accounts
    private Account newAccount(String name) {
        return new Account("AC" + (accountCounter++), name);
    }

    private String personName() {
        return FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)];
    }

    private void createAccounts() {
        for (String s : EMPLOYERS) employers.add(newAccount(s));
        for (String s : CLIENTS) clients.add(newAccount(s));
        for (String s : SHOPS) shops.add(newAccount(s));
        for (String s : SUPPLIERS) suppliers.add(newAccount(s));
        for (String s : UTILITIES) utilities.add(newAccount(s));
        for (int i = 0; i < 8; i++) landlords.add(newAccount(personName() + " (Landlord)"));
        for (int i = 0; i < 240; i++) people.add(newAccount(personName()));
    }

    // ---------------------------------------------------------------- normal life
    private void normalLife() {
        // clients pay employers big invoices during the month
        for (Account emp : employers) {
            for (int k = 0; k < 3; k++) {
                Account c = clients.get(rnd.nextInt(clients.size()));
                pay(c, emp, round(between(4_00_000, 9_00_000), 1000), at(rnd.nextInt(days), 11, 17), "NEFT");
            }
        }
        // the month's salary money arrives a few days BEFORE salary day (previous month's invoice)
        for (Account emp : employers) {
            pay(clients.get(rnd.nextInt(clients.size())), emp, round(between(8_00_000, 14_00_000), 1000),
                    LocalDateTime.of(start.minusDays(4 + rnd.nextInt(5)), java.time.LocalTime.of(12, 0)), "NEFT");
        }

        Collections.shuffle(people, rnd);
        int peopleCount = people.size();
        for (int p = 0; p < peopleCount; p++) {
            Account person = people.get(p);
            boolean employed = p < (int) (peopleCount * 0.72);
            boolean student = !employed && p < (int) (peopleCount * 0.88);
            long monthlyIncome = 0;

            if (employed) {
                Account emp = employers.get(p % employers.size());
                long salary = round(between(18_000, 85_000), 100);
                monthlyIncome = salary;
                pay(emp, person, salary, at(0, 10, 12), "NEFT");
            } else if (student) {
                Account parent = people.get(rnd.nextInt((int) (peopleCount * 0.72)));
                long pocket = round(between(4_000, 15_000), 500);
                monthlyIncome = pocket;
                pay(parent, person, pocket, at(rnd.nextInt(5), 8, 21), "UPI");
            } else {
                monthlyIncome = round(between(10_000, 30_000), 100);   // pension / small business, paid in cash earlier
                pay(clients.get(rnd.nextInt(clients.size())), person, monthlyIncome, at(1, 10, 13), "NEFT");
            }

            // rent
            if (employed && rnd.nextDouble() < 0.6) {
                long rent = round(Math.min(monthlyIncome * 0.35, between(6_000, 22_000)), 500);
                pay(person, landlords.get(rnd.nextInt(landlords.size())), rent, at(1 + rnd.nextInt(5), 9, 21), "UPI");
            }
            // bills
            for (Account u : utilities) {
                if (rnd.nextDouble() < 0.7) pay(person, u, round(between(300, 2_800), 10), at(5 + rnd.nextInt(20), 8, 22), "UPI");
            }
            // shopping at 3-5 favourite shops
            int favourites = 3 + rnd.nextInt(3);
            List<Account> fav = new ArrayList<>();
            for (int f = 0; f < favourites; f++) fav.add(shops.get(rnd.nextInt(shops.size())));
            int purchases = 8 + rnd.nextInt(10);
            for (int k = 0; k < purchases; k++) {
                long amount = round(between(40, Math.max(300, monthlyIncome / 25.0)), 10);
                pay(person, fav.get(rnd.nextInt(fav.size())), amount, at(rnd.nextInt(days), 7, 23), "UPI");
            }
            // friends and family
            int friends = 2 + rnd.nextInt(5);
            for (int k = 0; k < friends; k++) {
                Account friend = people.get(rnd.nextInt(peopleCount));
                if (friend == person) continue;
                long amount = rnd.nextDouble() < 0.06 ? round(between(10_000, 25_000), 500) : round(between(150, 6_000), 50);
                pay(person, friend, amount, at(rnd.nextInt(days), 8, 23), "UPI");
            }
        }

        // shops pay their wholesale suppliers every week (money stays a few days)
        for (Account shop : shops) {
            Account supplier = suppliers.get(rnd.nextInt(suppliers.size()));
            for (int week = 0; week < 4; week++) {
                pay(shop, supplier, round(between(6_000, 30_000), 100), at(week * 7 + 6, 11, 18), "NEFT");
            }
        }
        // landlords spend some of the rent later in the month
        for (Account landlord : landlords) {
            for (int k = 0; k < 3; k++) {
                pay(landlord, shops.get(rnd.nextInt(shops.size())), round(between(500, 4_000), 10), at(10 + rnd.nextInt(20), 9, 21), "UPI");
            }
        }

        // A legitimate group collection that LOOKS a bit like fraud (good test for false alarms):
        // 30 students pay the fest committee Rs 1,500 each, and the committee pays the venue.
        Account fest = newAccount("College Fest Committee");
        Account venue = newAccount("Grand Palace Banquet Hall");
        for (int k = 0; k < 30; k++) {
            Account student = people.get(rnd.nextInt(peopleCount));
            pay(student, fest, 1_500, at(14 + rnd.nextInt(3), 9, 21), "UPI");
        }
        pay(fest, venue, 42_000, at(18, 12, 14), "IMPS");
    }

    // ---------------------------------------------------------------- the scam
    /**
     * "Digital arrest" scam, 12 - 20 October:
     *  victims  -> 3 collector mules -> 6 layering mules -> 4 structuring mules -> 2 cash-out accounts
     *  plus a round-trip loop between four mules.
     */
    private void digitalArrestRing() {
        List<Account> collectors = mules(3);
        List<Account> layer2 = mules(6);
        List<Account> layer3 = mules(4);
        List<Account> cashOut = List.of(newAccount("QuickCoin P2P Trader"), newAccount("Gold Point Jewellers (Shell)"));

        // mules look a little like real people: a few small purchases each
        List<Account> allMules = new ArrayList<>(collectors);
        allMules.addAll(layer2);
        allMules.addAll(layer3);
        for (Account m : allMules) {
            for (int k = 0; k < 2 + rnd.nextInt(4); k++) {
                pay(m, shops.get(rnd.nextInt(shops.size())), round(between(80, 900), 10), at(rnd.nextInt(days), 9, 22), "UPI");
            }
        }

        // pick 22 victims among normal people
        Set<Account> victimSet = new HashSet<>();
        while (victimSet.size() < 22) victimSet.add(people.get(rnd.nextInt(people.size())));
        List<Account> victims = new ArrayList<>(victimSet);
        victims.sort(Comparator.comparing(Account::id));

        for (Account victim : victims) {
            int day = 11 + rnd.nextInt(8);
            Account collector = collectors.get(rnd.nextInt(collectors.size()));
            long total = round(between(60_000, 3_50_000), 1000);
            int parts = total > 2_00_000 ? 2 : 1;           // big losses are paid in two goes
            LocalDateTime time = at(day, 10, 17);
            for (int p = 0; p < parts; p++) {
                long amount = round((double) total / parts, 1000);
                pay(victim, collector, amount, time, "IMPS");
                launder(collector, amount, time, layer2, layer3, cashOut);
                time = time.plusMinutes(35 + rnd.nextInt(60));
            }
        }

        // Round-tripping loop: L2[0] -> L3[1] -> L2[3] -> C[2] -> L2[0], within ~2 days
        LocalDateTime t = at(16, 10, 12);
        long amount = 1_80_000;
        Account[] loop = {layer2.get(0), layer3.get(1), layer2.get(3), collectors.get(2), layer2.get(0)};
        for (int k = 0; k < loop.length - 1; k++) {
            pay(loop[k], loop[k + 1], amount, t, "IMPS");
            amount = round(amount * 0.985, 100);
            t = t.plusHours(4 + rnd.nextInt(8));
        }
    }

    private List<Account> mules(int count) {
        List<Account> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(newAccount(personName()));
        return list;
    }

    /** One stolen payment moving through the layers. Each mule keeps a 1-4% commission. */
    private void launder(Account collector, long amount, LocalDateTime received,
                         List<Account> layer2, List<Account> layer3, List<Account> cashOut) {
        long afterCut = round(amount * between(0.96, 0.98), 100);
        int splits = afterCut > 1_50_000 ? 3 : 2;
        LocalDateTime t1 = received.plusMinutes(5 + rnd.nextInt(30));
        long[] pieces = split(afterCut, splits);
        for (long piece : pieces) {
            Account l2 = layer2.get(rnd.nextInt(layer2.size()));
            pay(collector, l2, piece, t1, "IMPS");
            // layer 2 breaks the money into payments just under Rs 50,000 (structuring)
            long remaining = round(piece * between(0.97, 0.985), 100);
            LocalDateTime t2 = t1.plusMinutes(10 + rnd.nextInt(70));
            while (remaining > 0) {
                long chunk = remaining > 49_900 ? round(between(45_200, 49_900), 100) : remaining;
                if (remaining - chunk > 0 && remaining - chunk < 10_000) chunk = remaining;   // no tiny leftovers
                if (chunk >= 50_000) chunk = 49_900;
                Account l3 = layer3.get(rnd.nextInt(layer3.size()));
                pay(l2, l3, chunk, t2, "IMPS");
                // layer 3 forwards each chunk to a cash-out account after a short wait
                long forward = round(chunk * between(0.98, 0.99), 100);
                Account cash = cashOut.get(rnd.nextInt(cashOut.size()));
                pay(l3, cash, forward, t2.plusMinutes(30 + rnd.nextInt(150)), "IMPS");
                remaining -= chunk;
                t2 = t2.plusMinutes(3 + rnd.nextInt(12));
            }
            t1 = t1.plusMinutes(2 + rnd.nextInt(6));
        }
    }

    private long[] split(long total, int parts) {
        long[] pieces = new long[parts];
        long left = total;
        for (int i = 0; i < parts - 1; i++) {
            pieces[i] = round(total / (double) parts * between(0.8, 1.2), 100);
            left -= pieces[i];
        }
        pieces[parts - 1] = left;
        return pieces;
    }

    // ---------------------------------------------------------------- helpers
    private void pay(Account from, Account to, long amount, LocalDateTime time, String channel) {
        if (amount <= 0 || from.id().equals(to.id())) return;
        out.add(new Transaction("T" + (txnCounter++), from.id(), from.name(), to.id(), to.name(), amount, time, channel));
    }

    private LocalDateTime at(int dayIndex, int fromHour, int toHour) {
        int day = Math.max(0, Math.min(days - 1, dayIndex));
        int hour = fromHour + rnd.nextInt(Math.max(1, toHour - fromHour));
        return start.plusDays(day).atTime(hour, rnd.nextInt(60), rnd.nextInt(60));
    }

    private double between(double lo, double hi) {
        return lo + rnd.nextDouble() * (hi - lo);
    }

    private static long round(double value, long step) {
        return Math.max(step, Math.round(value / step) * step);
    }
}
