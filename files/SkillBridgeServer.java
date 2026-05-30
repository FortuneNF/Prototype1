/**
 * ============================================================
 *  SkillBridgeServer.java
 *  Author      : JGA Group [Your names here]
 *  Date        : June 2026
 *  Purpose     : Java HTTP REST API backend for SkillBridge SA.
 *                Uses only java.net.httpserver — no frameworks,
 *                no external libraries. Runs on port 8080.
 *
 *  Endpoints:
 *    GET  /api/tutors            — list all tutors
 *    GET  /api/tutors/{id}       — single tutor by ID
 *    GET  /api/tutors/subject/{n}— filter by subject index
 *    POST /api/cart/add          — add session to cart
 *    GET  /api/cart              — view current cart
 *    POST /api/cart/remove       — remove item from cart
 *    POST /api/cart/clear        — clear entire cart
 *    POST /api/checkout          — process checkout
 *    GET  /api/analytics         — statistics & projections
 *    GET  /api/revenue           — revenue model data
 *    GET  /api/health            — server health check
 *
 *  Compile : javac SkillBridgeServer.java
 *  Run     : java SkillBridgeServer
 * ============================================================
 */

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class SkillBridgeServer {

    // ============================================================
    //  CONSTANTS — fixed platform parameters
    // ============================================================
    static final int    PORT                = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final double PLATFORM_FEE_RATE   = 0.10;
    static final double SUBSCRIPTION_PRICE  = 99.00;
    static final double TUTOR_PREMIUM       = 199.00;
    static final int    MAX_HOURS           = 4;
    static final int    MIN_HOURS           = 1;

    // ============================================================
    //  IN-MEMORY DATA STORE
    //  In a real deployment this would be a database (MySQL / PostgreSQL)
    // ============================================================

    // Tutor records — each index represents one tutor
    static final int[]    TUTOR_ID       = {1, 2, 3, 4, 5, 6};
    static final String[] TUTOR_NAME     = {
        "Thabo Nkosi", "Amara Dlamini", "Kefilwe Mokoena",
        "Sipho Zulu",  "Naledi Sithole","Lwazi Cele"
    };
    static final String[] TUTOR_SUBJECT  = {
        "Mathematics", "Java Programming", "Physical Sciences",
        "English & Communication", "Accounting & Finance", "Chemistry"
    };
    static final String[] TUTOR_GRADE    = {
        "Gr 11/12 & 1st Year", "Beginner to Advanced",
        "Gr 11 & Gr 12",       "All levels",
        "Gr 10 to 12",         "Gr 11/12 & 1st Year"
    };
    static final double[] TUTOR_RATE     = {80.0, 120.0, 90.0, 60.0, 100.0, 85.0};
    static final double[] TUTOR_RATING   = {4.9, 4.8, 4.7, 4.6, 4.9, 4.5};
    static final int[]    TUTOR_SESSIONS = {142, 89, 67, 203, 118, 54};

    // Session-keyed carts — key = sessionId (from cookie), value = list of cart items
    // Each cart item is a Map with keys: tutorId, tutorName, subject, hours, rate, subtotal, fee, total
    static final Map<String, List<Map<String,Object>>> CARTS = new HashMap<>();

    // ============================================================
    //  LIVE ANALYTICS TRACKERS — start at zero, update on checkout
    //  These are variables: they change every time a purchase is made
    // ============================================================
    static int    TOTAL_CHECKOUTS      = 0;     // number of completed orders
    static int    TOTAL_SESSIONS_BOOKED= 0;     // total session bookings across all orders
    static double TOTAL_REVENUE        = 0.0;   // total money received (sessions + subscriptions)
    static double TOTAL_SESSION_REVENUE= 0.0;   // session revenue only (excl. subscription)
    static double TOTAL_SUBSCRIPTION_REVENUE = 0.0; // subscription revenue only
    static int    TOTAL_SUBSCRIBERS    = 0;     // unique checkouts = subscribers
    // Per-subject booking counts — index matches TUTOR_ID array
    static int[]  SUBJECT_BOOKINGS     = new int[6];
    // Per-order history: each entry is a small summary map
    static final List<Map<String,Object>> ORDER_HISTORY = new ArrayList<>();

    // ============================================================
    //  ENTRY POINT
    // ============================================================
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Register all route contexts
        server.createContext("/api/health",          new HealthHandler());
        server.createContext("/api/tutors",          new TutorHandler());
        server.createContext("/api/cart",            new CartHandler());
        server.createContext("/api/checkout",        new CheckoutHandler());
        server.createContext("/api/analytics",       new AnalyticsHandler());
        server.createContext("/api/revenue",         new RevenueHandler());
        // Serves index.html at http://localhost:8080/
        server.createContext("/",                    new StaticFileHandler());

        // Thread pool so multiple requests are handled concurrently
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("\u2554" + "\u2550".repeat(58) + "\u2557");
        System.out.println("\u2551  SkillBridge SA — Java Backend Server               \u2551");
        System.out.println("\u2560" + "\u2550".repeat(58) + "\u2563");
        System.out.println("\u2551  Open website : http://localhost:8080               \u2551");
        System.out.println("\u2551  Endpoints    : /api/tutors  /api/cart              \u2551");
        System.out.println("\u2551  Press Ctrl+C to stop the server.                   \u2551");
        System.out.println("\u255a" + "\u2550".repeat(58) + "\u255d");
    }

    // ============================================================
    //  HANDLER — GET /
    //  Serves index.html from the same folder as SkillBridgeServer.
    //  Both files must be in the same folder (e.g. C:\SkillBridge\)
    // ============================================================
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            // Only handle root — API routes are handled by their own contexts
            if (!path.equals("/") && !path.equals("/index.html")) {
                sendError(ex, 404, "Not found: " + path);
                return;
            }
            File file = new File("index.html");
            if (!file.exists()) {
                String msg = "index.html not found in: " + new File(".").getAbsolutePath()
                    + " — put index.html in the same folder as SkillBridgeServer.java";
                sendError(ex, 404, msg);
                System.out.println("  WARNING: " + msg);
                return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    // ============================================================
    //  HANDLER — GET /api/health
    //  Returns server status and uptime info
    // ============================================================
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equals("GET")) {
                sendError(ex, 405, "Method not allowed");
                return;
            }
            String json = "{"
                + "\"status\":\"UP\","
                + "\"service\":\"SkillBridge SA Backend\","
                + "\"version\":\"1.0\","
                + "\"port\":" + PORT + ","
                + "\"tutors\":" + TUTOR_ID.length + ","
                + "\"activeCarts\":" + CARTS.size()
                + "}";
            sendJson(ex, 200, json);
        }
    }

    // ============================================================
    //  HANDLER — /api/tutors
    //
    //  GET /api/tutors              → all tutors
    //  GET /api/tutors/{id}         → single tutor by ID (1-6)
    //  GET /api/tutors/subject/{n}  → filter tutors by subject index (1-6)
    // ============================================================
    static class TutorHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }
            if (!ex.getRequestMethod().equals("GET")) { sendError(ex, 405, "Method not allowed"); return; }

            String path = ex.getRequestURI().getPath(); // e.g. /api/tutors/3

            // Route: /api/tutors/subject/{n}
            if (path.contains("/subject/")) {
                String[] parts = path.split("/");
                String last = parts[parts.length - 1];

                // Input validation — must be integer 1-6
                if (!last.matches("\\d+")) {
                    sendError(ex, 400, "Subject index must be a number between 1 and 6");
                    return;
                }
                int subjectIndex = Integer.parseInt(last);
                if (subjectIndex < 1 || subjectIndex > TUTOR_ID.length) {
                    sendError(ex, 400, "Subject index out of range. Valid range: 1 to " + TUTOR_ID.length);
                    return;
                }
                // Filter: return only the tutor matching that subject slot
                String json = "[" + tutorJson(subjectIndex - 1) + "]";
                sendJson(ex, 200, json);
                return;
            }

            // Route: /api/tutors/{id}  — single tutor
            String[] segments = path.split("/");
            String lastSeg = segments[segments.length - 1];
            if (!lastSeg.equals("tutors") && lastSeg.matches("\\d+")) {
                int id = Integer.parseInt(lastSeg);
                int idx = findTutorIndex(id);
                if (idx == -1) {
                    sendError(ex, 404, "Tutor with ID " + id + " not found");
                    return;
                }
                sendJson(ex, 200, tutorJson(idx));
                return;
            }

            // Route: /api/tutors — all tutors
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < TUTOR_ID.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(tutorJson(i));
            }
            sb.append("]");
            sendJson(ex, 200, sb.toString());
        }
    }

    // ============================================================
    //  HANDLER — /api/cart
    //
    //  GET  /api/cart        → view cart for this session
    //  POST /api/cart/add    → add a session booking
    //                          Body: { "tutorId": 2, "hours": 2 }
    //  POST /api/cart/remove → remove one item
    //                          Body: { "index": 0 }
    //  POST /api/cart/clear  → clear all items
    // ============================================================
    static class CartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }

            String sessionId = getOrCreateSession(ex);
            List<Map<String,Object>> cart = CARTS.computeIfAbsent(sessionId, k -> new ArrayList<>());
            String path = ex.getRequestURI().getPath();

            // GET /api/cart — return current cart
            if (ex.getRequestMethod().equals("GET")) {
                sendJson(ex, 200, cartJson(cart));
                return;
            }

            if (!ex.getRequestMethod().equals("POST")) {
                sendError(ex, 405, "Method not allowed");
                return;
            }

            String body = readBody(ex);

            // POST /api/cart/add
            if (path.endsWith("/add")) {
                // Parse body: { "tutorId": 2, "hours": 2 }
                Integer tutorId = parseIntField(body, "tutorId");
                Integer hours   = parseIntField(body, "hours");

                // --- Input validation ---
                if (tutorId == null) {
                    sendError(ex, 400, "Missing or invalid 'tutorId'. Must be an integer 1-6.");
                    return;
                }
                if (hours == null) {
                    sendError(ex, 400, "Missing or invalid 'hours'. Must be an integer 1-4.");
                    return;
                }
                if (tutorId < 1 || tutorId > TUTOR_ID.length) {
                    sendError(ex, 400, "tutorId " + tutorId + " is out of range (1-" + TUTOR_ID.length + ").");
                    return;
                }
                if (hours < MIN_HOURS || hours > MAX_HOURS) {
                    sendError(ex, 400, "hours " + hours + " is out of range (" + MIN_HOURS + "-" + MAX_HOURS + ").");
                    return;
                }

                // Processing: calculate costs
                int    idx        = findTutorIndex(tutorId);
                double rate       = TUTOR_RATE[idx];
                double subtotal   = rate * hours;
                double fee        = round2(subtotal * PLATFORM_FEE_RATE);
                double total      = round2(subtotal + fee);

                // Build cart item map
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("tutorId",   tutorId);
                item.put("tutorName", TUTOR_NAME[idx]);
                item.put("subject",   TUTOR_SUBJECT[idx]);
                item.put("hours",     hours);
                item.put("rate",      rate);
                item.put("subtotal",  subtotal);
                item.put("fee",       fee);
                item.put("total",     total);
                cart.add(item);

                String resp = "{"
                    + "\"message\":\"Session added to cart\","
                    + "\"item\":" + mapToJson(item) + ","
                    + "\"cartSize\":" + cart.size()
                    + "}";
                sendJson(ex, 201, resp);
                return;
            }

            // POST /api/cart/remove
            if (path.endsWith("/remove")) {
                Integer index = parseIntField(body, "index");
                if (index == null || index < 0 || index >= cart.size()) {
                    sendError(ex, 400, "Invalid index. Cart has " + cart.size() + " item(s). Index must be 0 to " + (cart.size()-1));
                    return;
                }
                Map<String,Object> removed = cart.remove((int) index);
                String resp = "{"
                    + "\"message\":\"Item removed\","
                    + "\"removed\":" + mapToJson(removed) + ","
                    + "\"cartSize\":" + cart.size()
                    + "}";
                sendJson(ex, 200, resp);
                return;
            }

            // POST /api/cart/clear
            if (path.endsWith("/clear")) {
                int count = cart.size();
                cart.clear();
                sendJson(ex, 200, "{\"message\":\"Cart cleared\",\"itemsRemoved\":" + count + "}");
                return;
            }

            sendError(ex, 404, "Cart endpoint not found. Try /api/cart/add, /remove, /clear");
        }
    }

    // ============================================================
    //  HANDLER — POST /api/checkout
    //  Body: { "name": "Lerato Mokoena",
    //           "studentNumber": "20261234",
    //           "email": "lerato@student.tut.ac.za" }
    // ============================================================
    static class CheckoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }
            if (!ex.getRequestMethod().equals("POST"))  { sendError(ex, 405, "Method not allowed"); return; }

            String sessionId = getOrCreateSession(ex);
            List<Map<String,Object>> cart = CARTS.getOrDefault(sessionId, new ArrayList<>());

            // Guard: cart must not be empty
            if (cart.isEmpty()) {
                sendError(ex, 400, "Cart is empty. Add at least one session before checkout.");
                return;
            }

            String body = readBody(ex);

            // Parse and validate fields
            String name          = parseStringField(body, "name");
            String studentNumber = parseStringField(body, "studentNumber");
            String email         = parseStringField(body, "email");

            // --- Input validation ---
            if (name == null || name.trim().isEmpty()) {
                sendError(ex, 400, "Validation failed: 'name' is required and cannot be blank.");
                return;
            }
            if (studentNumber == null || !studentNumber.matches("\\d{8,9}")) {
                sendError(ex, 400, "Validation failed: 'studentNumber' must be 8 or 9 digits (numbers only).");
                return;
            }
            if (email == null || !isValidEmail(email)) {
                sendError(ex, 400, "Validation failed: 'email' must contain '@' and '.' (e.g. name@student.tut.ac.za).");
                return;
            }

            // Processing: calculate totals
            double sessionsTotal = 0;
            for (Map<String,Object> item : cart) {
                sessionsTotal += (double) item.get("total");
            }
            double grandTotal = round2(sessionsTotal + SUBSCRIPTION_PRICE);

            // Generate booking reference
            String reference = "SKB-" + (100000 + new Random().nextInt(900000));

            // Build response — order confirmation
            StringBuilder itemsJson = new StringBuilder("[");
            for (int i = 0; i < cart.size(); i++) {
                if (i > 0) itemsJson.append(",");
                itemsJson.append(mapToJson(cart.get(i)));
            }
            itemsJson.append("]");

            String resp = "{"
                + "\"success\":true,"
                + "\"reference\":\"" + reference + "\","
                + "\"student\":\"" + escapeJson(name) + "\","
                + "\"studentNumber\":\"" + studentNumber + "\","
                + "\"email\":\"" + escapeJson(email) + "\","
                + "\"items\":" + itemsJson + ","
                + "\"sessionsTotal\":" + sessionsTotal + ","
                + "\"subscription\":" + SUBSCRIPTION_PRICE + ","
                + "\"grandTotal\":" + grandTotal + ","
                + "\"message\":\"Payment successful. Confirmation sent to " + escapeJson(email) + ". Your tutors will contact you within 2 hours.\""
                + "}";

            // ---- Update live analytics trackers ----
            TOTAL_CHECKOUTS++;
            TOTAL_SUBSCRIBERS++;
            TOTAL_SESSION_REVENUE     = round2(TOTAL_SESSION_REVENUE + sessionsTotal);
            TOTAL_SUBSCRIPTION_REVENUE= round2(TOTAL_SUBSCRIPTION_REVENUE + SUBSCRIPTION_PRICE);
            TOTAL_REVENUE             = round2(TOTAL_REVENUE + grandTotal);

            // Count sessions and update per-subject booking counts
            for (Map<String,Object> item : cart) {
                TOTAL_SESSIONS_BOOKED++;
                int tid = (int) item.get("tutorId");
                int idx = findTutorIndex(tid);
                if (idx >= 0) SUBJECT_BOOKINGS[idx]++;
            }

            // Save to order history (keep last 50)
            Map<String,Object> order = new LinkedHashMap<>();
            order.put("reference",  reference);
            order.put("student",    name);
            order.put("email",      email);
            order.put("total",      grandTotal);
            order.put("sessions",   cart.size());
            order.put("timestamp",  System.currentTimeMillis());
            ORDER_HISTORY.add(order);
            if (ORDER_HISTORY.size() > 50) ORDER_HISTORY.remove(0);

            System.out.println("  [ORDER] " + reference + " | " + name
                + " | R" + grandTotal + " | Total revenue: R" + TOTAL_REVENUE);

            // Clear cart after successful checkout
            cart.clear();
            sendJson(ex, 200, resp);
        }
    }

    // ============================================================
    //  HANDLER — GET /api/analytics
    //  Returns Year-1 projections, elementary statistics, cost-benefit
    // ============================================================
    static class AnalyticsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }
            if (!ex.getRequestMethod().equals("GET")) { sendError(ex, 405, "Method not allowed"); return; }

            // ---- LIVE STATS from real purchases ----
            // All values start at zero and grow with every checkout

            // Elementary statistics on tutor hourly rates (fixed — tutor rates don't change)
            double sumRates = 0;
            for (double r : TUTOR_RATE) sumRates += r;
            double meanRate = round2(sumRates / TUTOR_RATE.length);

            double[] sorted = TUTOR_RATE.clone();
            bubbleSort(sorted);
            double medianRate = round2((sorted[2] + sorted[3]) / 2.0);
            double rangeRate  = round2(sorted[sorted.length - 1] - sorted[0]);

            // Mean sessions per subscriber (live — only meaningful after purchases)
            double meanSessionsPerSub = TOTAL_SUBSCRIBERS > 0
                ? round2((double) TOTAL_SESSIONS_BOOKED / TOTAL_SUBSCRIBERS)
                : 0;

            // Cost-benefit — costs are real fixed costs; revenue is live
            double hostingCost   = 12 * 350;
            double marketingCost = 12 * 500;
            double paymentFees   = round2(TOTAL_REVENUE * 0.029);
            double totalCost     = round2(hostingCost + marketingCost + paymentFees);
            double netProfit     = round2(TOTAL_REVENUE - totalCost);
            double cbRatio       = totalCost > 0 ? round2(TOTAL_REVENUE / totalCost) : 0;

            // Subject demand — live booking counts per subject
            StringBuilder demandJson = new StringBuilder("[");
            int totalBookings = 0;
            for (int b : SUBJECT_BOOKINGS) totalBookings += b;
            for (int i = 0; i < TUTOR_SUBJECT.length; i++) {
                if (i > 0) demandJson.append(",");
                int pct = totalBookings > 0
                    ? (int) Math.round((double) SUBJECT_BOOKINGS[i] / totalBookings * 100)
                    : 0;
                demandJson.append("{")
                    .append("\"subject\":\"").append(TUTOR_SUBJECT[i]).append("\",")
                    .append("\"bookings\":").append(SUBJECT_BOOKINGS[i]).append(",")
                    .append("\"percent\":").append(pct)
                    .append("}");
            }
            demandJson.append("]");

            // Order history — last 10 orders for the live feed
            StringBuilder historyJson = new StringBuilder("[");
            int start = Math.max(0, ORDER_HISTORY.size() - 10);
            for (int i = ORDER_HISTORY.size() - 1; i >= start; i--) {
                if (i < ORDER_HISTORY.size() - 1) historyJson.append(",");
                historyJson.append(mapToJson(ORDER_HISTORY.get(i)));
            }
            historyJson.append("]");

            String json = "{"
                + "\"live\":{"
                    + "\"totalCheckouts\":" + TOTAL_CHECKOUTS + ","
                    + "\"totalSubscribers\":" + TOTAL_SUBSCRIBERS + ","
                    + "\"totalSessionsBooked\":" + TOTAL_SESSIONS_BOOKED + ","
                    + "\"totalRevenue\":" + TOTAL_REVENUE + ","
                    + "\"sessionRevenue\":" + TOTAL_SESSION_REVENUE + ","
                    + "\"subscriptionRevenue\":" + TOTAL_SUBSCRIPTION_REVENUE
                + "},"
                + "\"stats\":{"
                    + "\"meanHourlyRate\":" + meanRate + ","
                    + "\"medianHourlyRate\":" + medianRate + ","
                    + "\"rangeHourlyRate\":" + rangeRate + ","
                    + "\"minRate\":" + sorted[0] + ","
                    + "\"maxRate\":" + sorted[sorted.length - 1] + ","
                    + "\"meanSessionsPerSub\":" + meanSessionsPerSub + ","
                    + "\"ytdRevenue\":" + TOTAL_REVENUE + ","
                    + "\"subscriberGrowthPercent\":0"
                + "},"
                + "\"costBenefit\":{"
                    + "\"totalRevenue\":" + TOTAL_REVENUE + ","
                    + "\"hostingCost\":" + hostingCost + ","
                    + "\"marketingCost\":" + marketingCost + ","
                    + "\"paymentFees\":" + paymentFees + ","
                    + "\"totalCost\":" + totalCost + ","
                    + "\"netProfit\":" + netProfit + ","
                    + "\"costBenefitRatio\":" + cbRatio
                + "},"
                + "\"subjectDemand\":" + demandJson + ","
                + "\"recentOrders\":" + historyJson
                + "}";

            sendJson(ex, 200, json);
        }
    }

        // ============================================================
    //  HANDLER — GET /api/revenue
    //  Returns revenue model projections
    // ============================================================
    static class RevenueHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }
            if (!ex.getRequestMethod().equals("GET")) { sendError(ex, 405, "Method not allowed"); return; }

            double avgRate = 0;
            for (double r : TUTOR_RATE) avgRate += r;
            avgRate = round2(avgRate / TUTOR_RATE.length);

            double subRevM12       = round2(820 * SUBSCRIPTION_PRICE);
            double commissionRevM12= round2(2000 * 4.2 * avgRate * PLATFORM_FEE_RATE);
            double premiumRevM12   = round2(6 * TUTOR_PREMIUM);
            double totalRevM12     = round2(subRevM12 + commissionRevM12 + premiumRevM12);

            String json = "{"
                + "\"streams\":["
                    + "{\"name\":\"Student Subscription\",\"price\":" + SUBSCRIPTION_PRICE
                        + ",\"unit\":\"per student per month\","
                        + "\"month12Revenue\":" + subRevM12 + "},"
                    + "{\"name\":\"Session Commission\",\"rate\":" + PLATFORM_FEE_RATE
                        + ",\"description\":\"10% of each session booked\","
                        + "\"month12Revenue\":" + commissionRevM12 + "},"
                    + "{\"name\":\"Tutor Premium Listing\",\"price\":" + TUTOR_PREMIUM
                        + ",\"unit\":\"per tutor per month\","
                        + "\"month12Revenue\":" + premiumRevM12 + "}"
                + "],"
                + "\"month12Total\":" + totalRevM12 + ","
                + "\"platformFeeRate\":" + PLATFORM_FEE_RATE + ","
                + "\"subscriptionPrice\":" + SUBSCRIPTION_PRICE + ","
                + "\"premiumListingPrice\":" + TUTOR_PREMIUM + ","
                + "\"avgTutorRate\":" + avgRate + ","
                + "\"noCodeTools\":["
                    + "{\"name\":\"Zapier\",\"role\":\"Automates booking emails and tutor payment notifications\"},"
                    + "{\"name\":\"Tidio AI Chatbot\",\"role\":\"Handles tutor-matching queries 24/7\"},"
                    + "{\"name\":\"Stripe Payments\",\"role\":\"ZAR transactions with fraud protection\"},"
                    + "{\"name\":\"Calendly\",\"role\":\"Tutor availability and student booking\"}"
                + "]"
                + "}";
            sendJson(ex, 200, json);
        }
    }

    // ============================================================
    //  UTILITY METHODS
    // ============================================================

    /** Build JSON object for one tutor by array index */
    static String tutorJson(int i) {
        return "{"
            + "\"id\":"        + TUTOR_ID[i]       + ","
            + "\"name\":\""    + TUTOR_NAME[i]     + "\","
            + "\"subject\":\"" + TUTOR_SUBJECT[i]  + "\","
            + "\"grade\":\""   + escapeJson(TUTOR_GRADE[i]) + "\","
            + "\"rate\":"      + TUTOR_RATE[i]     + ","
            + "\"rating\":"    + TUTOR_RATING[i]   + ","
            + "\"sessions\":"  + TUTOR_SESSIONS[i]
            + "}";
    }

    /** Build JSON for the full cart including totals */
    static String cartJson(List<Map<String,Object>> cart) {
        double cartTotal = 0;
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < cart.size(); i++) {
            if (i > 0) items.append(",");
            Map<String,Object> item = cart.get(i);
            items.append(mapToJson(item));
            cartTotal += (double) item.get("total");
        }
        items.append("]");
        return "{"
            + "\"items\":"          + items + ","
            + "\"itemCount\":"      + cart.size() + ","
            + "\"sessionsTotal\":"  + round2(cartTotal) + ","
            + "\"subscription\":"   + SUBSCRIPTION_PRICE + ","
            + "\"grandTotal\":"     + round2(cartTotal + SUBSCRIPTION_PRICE)
            + "}";
    }

    /** Convert a Map<String,Object> to a JSON string */
    static String mapToJson(Map<String,Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String,Object> e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String) sb.append("\"").append(escapeJson((String)v)).append("\"");
            else sb.append(v);
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /** Find tutor array index by tutor ID */
    static int findTutorIndex(int id) {
        for (int i = 0; i < TUTOR_ID.length; i++) {
            if (TUTOR_ID[i] == id) return i;
        }
        return -1;
    }

    /** Validate email: must have @ and . after @ */
    static boolean isValidEmail(String email) {
        int at  = email.indexOf('@');
        int dot = email.lastIndexOf('.');
        return at > 0 && dot > at + 1 && dot < email.length() - 1;
    }

    /** Bubble sort ascending (for median / statistics calculations) */
    static void bubbleSort(double[] arr) {
        for (int i = 0; i < arr.length - 1; i++) {
            for (int j = 0; j < arr.length - i - 1; j++) {
                if (arr[j] > arr[j+1]) {
                    double tmp = arr[j]; arr[j] = arr[j+1]; arr[j+1] = tmp;
                }
            }
        }
    }

    /** Round to 2 decimal places */
    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Read the full request body as a UTF-8 string */
    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Parse an integer value from a simple JSON body string.
     * Looks for "key": number  (handles spaces, no library needed)
     */
    static Integer parseIntField(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon == -1) return null;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (start == end) return null;
        try { return Integer.parseInt(json.substring(start, end)); } catch (NumberFormatException e) { return null; }
    }

    /** Parse a string value from a simple JSON body */
    static String parseStringField(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon == -1) return null;
        int open = json.indexOf('"', colon + 1);
        if (open == -1) return null;
        int close = json.indexOf('"', open + 1);
        if (close == -1) return null;
        return json.substring(open + 1, close);
    }

    /** Escape special characters in JSON strings */
    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Set CORS headers so the frontend HTML can call the API */
    static void setCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    /** Manage session IDs via cookies */
    static String getOrCreateSession(HttpExchange ex) {
        // Try to read existing session cookie
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies != null) {
            for (String c : cookies) {
                for (String part : c.split(";")) {
                    part = part.trim();
                    if (part.startsWith("sbsid=")) {
                        return part.substring(6);
                    }
                }
            }
        }
        // Create new session ID
        String newId = "sb-" + UUID.randomUUID().toString().replace("-","").substring(0,12);
        ex.getResponseHeaders().add("Set-Cookie", "sbsid=" + newId + "; Path=/; SameSite=Lax");
        return newId;
    }

    /** Send a JSON response with appropriate headers */
    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /** Send a JSON error response */
    static void sendError(HttpExchange ex, int code, String message) throws IOException {
        String json = "{\"error\":true,\"code\":" + code + ",\"message\":\"" + escapeJson(message) + "\"}";
        sendJson(ex, code, json);
    }

} // end class SkillBridgeServer