import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Automated Library / Bookstore Management System
 * ------------------------------------------------
 * Features:
 *  - Auto-segregation of books into Section (genre) -> Rack (A-Z) -> Shelf
 *  - Customer search (title / author / series / genre) with exact shelf location
 *  - Price calculation (condition discount + bulk discount) and bill generation
 *  - Purchase history saved to file
 *  - Series sold-out tracking and book condition tracking
 *  - Librarian tools: add / restock / remove / update condition / reports
 *
 * Compile: javac LibrarySystem.java
 * Run    : java LibrarySystem
 * Data   : books.csv and purchases.csv are created automatically.
 */

// ---------------------------------------------------------------- Condition
enum Condition {
    NEW(0, true), GOOD(0, true), FAIR(20, true), DAMAGED(100, false);

    final int discountPercent;   // discount given for worn books
    final boolean sellable;      // damaged books cannot be sold

    Condition(int discountPercent, boolean sellable) {
        this.discountPercent = discountPercent;
        this.sellable = sellable;
    }
}

// --------------------------------------------------------------------- Book
class Book {
    int id;
    String title, author, series, genre;
    double price;
    int quantity, sold;
    Condition condition;
    String location = "-";   // assigned automatically by Inventory.reorganize()

    Book(int id, String title, String author, String series, String genre,
         double price, int quantity, int sold, Condition condition) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.series = series;
        this.genre = genre;
        this.price = price;
        this.quantity = quantity;
        this.sold = sold;
        this.condition = condition;
    }

    boolean isAvailable() {
        return quantity > 0 && condition.sellable;
    }

    double sellingPrice() {
        return price * (100 - condition.discountPercent) / 100.0;
    }

    String status() {
        if (condition == Condition.DAMAGED) return "UNAVAILABLE (Damaged)";
        if (quantity == 0) return "SOLD OUT";
        if (quantity <= 2) return "Low stock";
        return "In stock";
    }

    String toCsv() {
        return id + "," + title + "," + author + "," + series + "," + genre + ","
                + price + "," + quantity + "," + sold + "," + condition;
    }

    static Book fromCsv(String line) {
        String[] p = line.split(",", -1);
        return new Book(Integer.parseInt(p[0]), p[1], p[2], p[3], p[4],
                Double.parseDouble(p[5]), Integer.parseInt(p[6]),
                Integer.parseInt(p[7]), Condition.valueOf(p[8]));
    }
}

// ---------------------------------------------------------------- Inventory
class Inventory {
    static final String FILE = "books.csv";
    static final int SHELF_CAPACITY = 5;   // books per shelf

    private final List<Book> books = new ArrayList<>();
    private int nextId = 1;

    Inventory() {
        if (Files.exists(Paths.get(FILE))) load();
        else seed();
        reorganize();
        save();
    }

    List<Book> all() { return books; }

    void load() {
        try {
            for (String line : Files.readAllLines(Paths.get(FILE))) {
                if (line.trim().isEmpty()) continue;
                Book b = Book.fromCsv(line);
                books.add(b);
                nextId = Math.max(nextId, b.id + 1);
            }
        } catch (Exception e) {
            System.out.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    void save() {
        List<String> lines = new ArrayList<>();
        for (Book b : books) lines.add(b.toCsv());
        try {
            Files.write(Paths.get(FILE), lines);
        } catch (IOException e) {
            System.out.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }

    Book add(String title, String author, String series, String genre,
             double price, int qty, Condition c) {
        Book b = new Book(nextId++, title, author, series, genre, price, qty, 0, c);
        books.add(b);
        reorganize();
        save();
        return b;
    }

    boolean remove(int id) {
        boolean removed = books.removeIf(b -> b.id == id);
        if (removed) { reorganize(); save(); }
        return removed;
    }

    Book find(int id) {
        for (Book b : books) if (b.id == id) return b;
        return null;
    }

    List<Book> search(String keyword) {
        String k = keyword.toLowerCase();
        List<Book> result = new ArrayList<>();
        for (Book b : books) {
            if (b.title.toLowerCase().contains(k) || b.author.toLowerCase().contains(k)
                    || b.series.toLowerCase().contains(k) || b.genre.toLowerCase().contains(k)) {
                result.add(b);
            }
        }
        return result;
    }

    List<Book> byGenre(String genre) {
        List<Book> result = new ArrayList<>();
        for (Book b : books) if (b.genre.equalsIgnoreCase(genre)) result.add(b);
        return result;
    }

    TreeSet<String> genres() {
        TreeSet<String> g = new TreeSet<>();
        for (Book b : books) g.add(b.genre);
        return g;
    }

    // ---------- automatic segregation ----------
    static String sectionOf(Book b) {
        String s = b.genre.toUpperCase().replaceAll("[^A-Z]", "");
        return s.length() > 4 ? s.substring(0, 4) : s;
    }

    static char rackOf(Book b) {
        String key = b.series.equals("-") ? b.title : b.series;
        char c = Character.toUpperCase(key.charAt(0));
        return Character.isLetter(c) ? c : '#';
    }

    /** Groups books by Section + Rack, sorts them (series, then title) and assigns shelves. */
    void reorganize() {
        Map<String, List<Book>> groups = new TreeMap<>();
        for (Book b : books) {
            groups.computeIfAbsent(sectionOf(b) + "-" + rackOf(b), k -> new ArrayList<>()).add(b);
        }
        for (Map.Entry<String, List<Book>> e : groups.entrySet()) {
            List<Book> list = e.getValue();
            list.sort(Comparator.comparing((Book b) -> b.series.toLowerCase())
                    .thenComparing(b -> b.title.toLowerCase()));
            for (int i = 0; i < list.size(); i++) {
                list.get(i).location = e.getKey() + "-S" + (i / SHELF_CAPACITY + 1);
            }
        }
    }

    // ---------- series tracking ----------
    TreeMap<String, List<Book>> seriesMap() {
        TreeMap<String, List<Book>> map = new TreeMap<>();
        for (Book b : books) {
            if (!b.series.equals("-")) map.computeIfAbsent(b.series, k -> new ArrayList<>()).add(b);
        }
        return map;
    }

    String seriesStatus(String series) {
        List<Book> vols = seriesMap().get(series);
        if (vols == null) return "";
        int avail = 0;
        for (Book b : vols) if (b.isAvailable()) avail++;
        String state = avail == 0 ? "SOLD OUT" : (avail == vols.size() ? "AVAILABLE" : "PARTIALLY AVAILABLE");
        return series + ": " + avail + "/" + vols.size() + " volumes available -> " + state;
    }

    private void seed() {
        add0("Harry Potter and the Philosopher's Stone", "J.K. Rowling", "Harry Potter", "Fantasy", 450, 4, 12, Condition.GOOD);
        add0("Harry Potter and the Chamber of Secrets", "J.K. Rowling", "Harry Potter", "Fantasy", 460, 0, 15, Condition.GOOD);
        add0("Harry Potter and the Prisoner of Azkaban", "J.K. Rowling", "Harry Potter", "Fantasy", 480, 3, 9, Condition.FAIR);
        add0("The Lightning Thief", "Rick Riordan", "Percy Jackson", "Fantasy", 350, 5, 6, Condition.NEW);
        add0("The Sea of Monsters", "Rick Riordan", "Percy Jackson", "Fantasy", 350, 0, 7, Condition.NEW);
        add0("Atomic Habits", "James Clear", "-", "Self Help", 399, 6, 20, Condition.NEW);
        add0("Java: The Complete Reference", "Herbert Schildt", "-", "Computer Science", 850, 3, 4, Condition.GOOD);
        add0("Introduction to Algorithms", "Thomas Cormen", "-", "Computer Science", 1200, 2, 3, Condition.GOOD);
        add0("Artificial Intelligence: A Modern Approach", "Stuart Russell", "-", "Computer Science", 1100, 1, 2, Condition.DAMAGED);
        add0("Python Crash Course", "Eric Matthes", "-", "Computer Science", 650, 5, 8, Condition.NEW);
        add0("The Hobbit", "J.R.R. Tolkien", "Middle Earth", "Fantasy", 399, 2, 5, Condition.FAIR);
        add0("A Brief History of Time", "Stephen Hawking", "-", "Science", 420, 4, 10, Condition.GOOD);
    }

    private void add0(String t, String a, String s, String g, double p, int q, int sold, Condition c) {
        books.add(new Book(nextId++, t, a, s, g, p, q, sold, c));
    }
}

// ------------------------------------------------------------ Purchase log
class PurchaseLog {
    static final String FILE = "purchases.csv";   // billNo,date,customer,bookId,title,qty,netAmount

    static int nextBillNo() {
        int max = 1000;
        for (String[] r : readAll()) max = Math.max(max, Integer.parseInt(r[0]));
        return max + 1;
    }

    static void append(String line) {
        try (FileWriter fw = new FileWriter(FILE, true)) {
            fw.write(line + System.lineSeparator());
        } catch (IOException e) {
            System.out.println("Could not record purchase: " + e.getMessage());
        }
    }

    static List<String[]> readAll() {
        List<String[]> rows = new ArrayList<>();
        if (!Files.exists(Paths.get(FILE))) return rows;
        try {
            for (String l : Files.readAllLines(Paths.get(FILE))) {
                if (!l.trim().isEmpty()) rows.add(l.split(",", -1));
            }
        } catch (IOException e) {
            System.out.println("Could not read purchases: " + e.getMessage());
        }
        return rows;
    }
}

// --------------------------------------------------------------------- Main
public class LibrarySystem {
    static final String LIBRARIAN_PIN = "1234";
    static final Scanner sc = new Scanner(System.in);
    static final Inventory inv = new Inventory();

    public static void main(String[] args) {
        while (true) {
            System.out.println("\n===== AUTOMATED LIBRARY MANAGEMENT SYSTEM =====");
            System.out.println("1. Customer");
            System.out.println("2. Librarian");
            System.out.println("0. Exit");
            switch (readInt("Choose: ")) {
                case 1: customerMenu(); break;
                case 2: if (login()) librarianMenu(); break;
                case 0: System.out.println("Goodbye!"); return;
                default: System.out.println("Invalid choice.");
            }
        }
    }

    // ------------------------------------------------------------- helpers
    static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return Integer.parseInt(sc.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid number.");
            }
        }
    }

    static double readDouble(String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                double d = Double.parseDouble(sc.nextLine().trim());
                if (d >= 0) return d;
            } catch (NumberFormatException ignored) { }
            System.out.println("Please enter a valid positive amount.");
        }
    }

    static String readText(String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine().trim().replace(",", ";");
            if (!s.isEmpty()) return s;
            System.out.println("This field cannot be empty.");
        }
    }

    static Condition readCondition() {
        Condition[] all = Condition.values();
        for (int i = 0; i < all.length; i++) System.out.println("  " + (i + 1) + ". " + all[i]);
        while (true) {
            int c = readInt("Condition (1-" + all.length + "): ");
            if (c >= 1 && c <= all.length) return all[c - 1];
            System.out.println("Invalid choice.");
        }
    }

    static void printBooks(List<Book> list) {
        if (list.isEmpty()) {
            System.out.println("No books found.");
            return;
        }
        String fmt = "%-4s %-34.34s %-18.18s %-14.14s %-9s %-4s %-8s %-12s %s%n";
        System.out.printf(fmt, "ID", "Title", "Author", "Series", "Price", "Qty", "Cond.", "Location", "Status");
        System.out.println("-".repeat(124));
        for (Book b : list) {
            System.out.printf(fmt, b.id, b.title, b.author, b.series,
                    String.format("Rs.%.0f", b.sellingPrice()), b.quantity,
                    b.condition, b.location, b.status());
        }
    }

    static void printSeriesOf(List<Book> list) {
        Set<String> shown = new TreeSet<>();
        for (Book b : list) if (!b.series.equals("-")) shown.add(b.series);
        if (shown.isEmpty()) return;
        System.out.println("\nSeries availability:");
        for (String s : shown) System.out.println("  * " + inv.seriesStatus(s));
    }

    static void describeLocation(Book b) {
        String[] p = b.location.split("-");
        System.out.println("  Section: " + p[0] + "  |  Rack: " + p[1] + "  |  Shelf: " + p[2].substring(1));
    }

    // ---------------------------------------------------------- customer
    static void customerMenu() {
        while (true) {
            System.out.println("\n--- CUSTOMER MENU ---");
            System.out.println("1. Browse all books");
            System.out.println("2. Search (title / author / series / genre)");
            System.out.println("3. Browse by genre");
            System.out.println("4. Buy books");
            System.out.println("0. Back");
            switch (readInt("Choose: ")) {
                case 1:
                    printBooks(inv.all());
                    printSeriesOf(inv.all());
                    break;
                case 2:
                    List<Book> res = inv.search(readText("Enter keyword: "));
                    printBooks(res);
                    printSeriesOf(res);
                    for (Book b : res) {
                        if (b.isAvailable()) {
                            System.out.println("\nFind \"" + b.title + "\" at:");
                            describeLocation(b);
                        }
                    }
                    break;
                case 3:
                    System.out.println("Genres: " + inv.genres());
                    List<Book> g = inv.byGenre(readText("Enter genre: "));
                    printBooks(g);
                    printSeriesOf(g);
                    break;
                case 4: buyBooks(); break;
                case 0: return;
                default: System.out.println("Invalid choice.");
            }
        }
    }

    static void buyBooks() {
        String customer = readText("Customer name: ");
        Map<Integer, Integer> cart = new LinkedHashMap<>();
        System.out.println("Enter Book ID and quantity to add. Enter 0 as ID to finish.");

        while (true) {
            int id = readInt("Book ID: ");
            if (id == 0) break;
            Book b = inv.find(id);
            if (b == null) { System.out.println("No book with that ID."); continue; }
            if (!b.isAvailable()) { System.out.println("Sorry, '" + b.title + "' is " + b.status() + "."); continue; }
            int qty = readInt("Quantity: ");
            int already = cart.getOrDefault(id, 0);
            if (qty <= 0) { System.out.println("Quantity must be at least 1."); continue; }
            if (qty + already > b.quantity) {
                System.out.println("Only " + b.quantity + " copies in stock (you already have " + already + " in cart).");
                continue;
            }
            cart.put(id, qty + already);
            System.out.println("Added: " + b.title + " x" + qty);
        }

        if (cart.isEmpty()) { System.out.println("Cart is empty. Nothing purchased."); return; }

        // ----- price calculation -----
        int totalQty = 0;
        double subtotal = 0;
        for (Map.Entry<Integer, Integer> e : cart.entrySet()) {
            totalQty += e.getValue();
            subtotal += inv.find(e.getKey()).sellingPrice() * e.getValue();
        }
        int bulkPercent = totalQty >= 5 ? 10 : (totalQty >= 3 ? 5 : 0);
        double bulkDiscount = subtotal * bulkPercent / 100.0;
        double total = subtotal - bulkDiscount;

        int billNo = PurchaseLog.nextBillNo();
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));

        System.out.println("\n=============== BILL #" + billNo + " ===============");
        System.out.println("Customer: " + customer + "    Date: " + date);
        System.out.println("-".repeat(60));
        System.out.printf("%-34.34s %-4s %-9s %s%n", "Book", "Qty", "Rate", "Amount");
        for (Map.Entry<Integer, Integer> e : cart.entrySet()) {
            Book b = inv.find(e.getKey());
            String note = b.condition.discountPercent > 0 ? " (" + b.condition + " -" + b.condition.discountPercent + "%)" : "";
            System.out.printf("%-34.34s %-4d %-9.2f %.2f%s%n", b.title, e.getValue(), b.sellingPrice(),
                    b.sellingPrice() * e.getValue(), note);
        }
        System.out.println("-".repeat(60));
        System.out.printf("Subtotal : Rs.%.2f%n", subtotal);
        System.out.printf("Bulk discount (%d%%): -Rs.%.2f%n", bulkPercent, bulkDiscount);
        System.out.printf("TOTAL    : Rs.%.2f%n", total);
        System.out.println("=".repeat(60));

        System.out.print("Confirm purchase? (y/n): ");
        if (!sc.nextLine().trim().equalsIgnoreCase("y")) { System.out.println("Purchase cancelled."); return; }

        // ----- update stock + record purchase -----
        for (Map.Entry<Integer, Integer> e : cart.entrySet()) {
            Book b = inv.find(e.getKey());
            b.quantity -= e.getValue();
            b.sold += e.getValue();
            double net = b.sellingPrice() * e.getValue() * (100 - bulkPercent) / 100.0;
            PurchaseLog.append(billNo + "," + date + "," + customer + "," + b.id + ","
                    + b.title + "," + e.getValue() + "," + String.format("%.2f", net));
            if (b.quantity == 0) {
                System.out.println("[Alert] '" + b.title + "' is now SOLD OUT.");
                if (!b.series.equals("-")) System.out.println("        " + inv.seriesStatus(b.series));
            }
        }
        inv.save();
        System.out.println("Thank you for your purchase!");
    }

    // --------------------------------------------------------- librarian
    static boolean login() {
        System.out.print("Enter librarian PIN: ");
        if (sc.nextLine().trim().equals(LIBRARIAN_PIN)) return true;
        System.out.println("Wrong PIN.");
        return false;
    }

    static void librarianMenu() {
        while (true) {
            System.out.println("\n--- LIBRARIAN MENU ---");
            System.out.println("1.  Add new book (auto-assigns shelf)");
            System.out.println("2.  View full inventory");
            System.out.println("3.  Restock a book");
            System.out.println("4.  Update book condition");
            System.out.println("5.  Remove a book");
            System.out.println("6.  Shelf map (where everything is kept)");
            System.out.println("7.  Series status report");
            System.out.println("8.  Sold-out / low-stock report");
            System.out.println("9.  Damaged books report");
            System.out.println("10. Sales & purchase report");
            System.out.println("11. Re-organize all shelves");
            System.out.println("0.  Back");
            switch (readInt("Choose: ")) {
                case 1: addBook(); break;
                case 2: printBooks(inv.all()); break;
                case 3: restock(); break;
                case 4: updateCondition(); break;
                case 5: removeBook(); break;
                case 6: shelfMap(); break;
                case 7: seriesReport(); break;
                case 8: stockReport(); break;
                case 9: damagedReport(); break;
                case 10: salesReport(); break;
                case 11:
                    inv.reorganize();
                    inv.save();
                    System.out.println("All books re-sorted by Section -> Rack -> Series -> Title.");
                    break;
                case 0: return;
                default: System.out.println("Invalid choice.");
            }
        }
    }

    static void addBook() {
        String title = readText("Title: ");
        String author = readText("Author: ");
        System.out.print("Series name (press Enter if none): ");
        String series = sc.nextLine().trim().replace(",", ";");
        if (series.isEmpty()) series = "-";
        System.out.println("Existing genres: " + inv.genres());
        String genre = readText("Genre: ");
        double price = readDouble("Price (Rs.): ");
        int qty = readInt("Quantity: ");
        if (qty < 0) { System.out.println("Quantity cannot be negative."); return; }
        Condition c = readCondition();
        Book b = inv.add(title, author, series, genre, price, qty, c);
        System.out.println("Book added with ID " + b.id + ". Place it here (location may shift for neighbours):");
        describeLocation(b);
    }

    static void restock() {
        Book b = inv.find(readInt("Book ID: "));
        if (b == null) { System.out.println("Book not found."); return; }
        int add = readInt("Copies to add: ");
        if (add <= 0) { System.out.println("Enter a positive number."); return; }
        boolean wasSoldOut = b.quantity == 0;
        b.quantity += add;
        inv.save();
        System.out.println("Stock updated: " + b.title + " now has " + b.quantity + " copies.");
        if (wasSoldOut) System.out.println("Book is available again. " + inv.seriesStatus(b.series));
    }

    static void updateCondition() {
        Book b = inv.find(readInt("Book ID: "));
        if (b == null) { System.out.println("Book not found."); return; }
        System.out.println("Current condition of '" + b.title + "': " + b.condition);
        b.condition = readCondition();
        inv.save();
        System.out.println("Condition updated. Selling price now Rs." + String.format("%.2f", b.sellingPrice())
                + " (" + b.status() + ")");
    }

    static void removeBook() {
        int id = readInt("Book ID to remove: ");
        System.out.println(inv.remove(id) ? "Book removed and shelves re-organized." : "Book not found.");
    }

    static void shelfMap() {
        Map<String, List<Book>> map = new TreeMap<>();
        for (Book b : inv.all()) map.computeIfAbsent(b.location, k -> new ArrayList<>()).add(b);
        System.out.println("\n===== SHELF MAP (Section-Rack-Shelf) =====");
        for (Map.Entry<String, List<Book>> e : map.entrySet()) {
            System.out.println("[" + e.getKey() + "]");
            for (Book b : e.getValue()) {
                System.out.printf("    #%-3d %-40.40s %-8s %s%n", b.id, b.title, b.condition, b.status());
            }
        }
    }

    static void seriesReport() {
        TreeMap<String, List<Book>> map = inv.seriesMap();
        if (map.isEmpty()) { System.out.println("No book series in inventory."); return; }
        System.out.println("\n===== SERIES STATUS =====");
        for (String s : map.keySet()) {
            System.out.println(inv.seriesStatus(s));
            for (Book b : map.get(s)) System.out.println("     - " + b.title + " [" + b.status() + "]");
        }
    }

    static void stockReport() {
        List<Book> out = new ArrayList<>(), low = new ArrayList<>();
        for (Book b : inv.all()) {
            if (b.quantity == 0) out.add(b);
            else if (b.quantity <= 2) low.add(b);
        }
        System.out.println("\n--- SOLD OUT (needs restocking) ---");
        printBooks(out);
        System.out.println("\n--- LOW STOCK (2 or fewer copies) ---");
        printBooks(low);
    }

    static void damagedReport() {
        List<Book> list = new ArrayList<>();
        for (Book b : inv.all()) if (b.condition == Condition.DAMAGED || b.condition == Condition.FAIR) list.add(b);
        System.out.println("\n--- BOOKS NEEDING ATTENTION (FAIR / DAMAGED) ---");
        printBooks(list);
        System.out.println("Note: DAMAGED books cannot be sold; FAIR books get a 20% discount.");
    }

    static void salesReport() {
        List<String[]> rows = PurchaseLog.readAll();
        if (rows.isEmpty()) { System.out.println("No purchases recorded yet."); return; }
        System.out.println("\n===== PURCHASE HISTORY =====");
        System.out.printf("%-6s %-17s %-14.14s %-34.34s %-4s %s%n", "Bill", "Date", "Customer", "Book", "Qty", "Amount");
        double revenue = 0;
        int copies = 0;
        Set<String> bills = new HashSet<>();
        Map<String, Integer> perBook = new TreeMap<>();
        for (String[] r : rows) {
            System.out.printf("%-6s %-17s %-14.14s %-34.34s %-4s %s%n", r[0], r[1], r[2], r[4], r[5], r[6]);
            revenue += Double.parseDouble(r[6]);
            copies += Integer.parseInt(r[5]);
            bills.add(r[0]);
            perBook.merge(r[4], Integer.parseInt(r[5]), Integer::sum);
        }
        String best = Collections.max(perBook.entrySet(), Map.Entry.comparingByValue()).getKey();
        System.out.println("-".repeat(90));
        System.out.printf("Total bills: %d | Copies sold: %d | Revenue: Rs.%.2f%n", bills.size(), copies, revenue);
        System.out.println("Best seller (this log): " + best);
    }
}