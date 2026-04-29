import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import javax.swing.*;


public class QuickMartApp {

    // ── DB config  ← CHANGE DB_PASS ──────────────────────────────────────
    static final String DB_URL  = "jdbc:mysql://localhost:3306/quickmart"
                                + "?useSSL=false&serverTimezone=UTC"
                                + "&allowPublicKeyRetrieval=true"
                                + "&connectTimeout=3000";
    static final String DB_USER = "root";
    static final String DB_PASS = "your_password_here";   // ← PUT YOUR PASSWORD HERE

    // ── Runtime flags ─────────────────────────────────────────────────────
    static boolean dbAvailable = false;
    static boolean dbChecked   = false;

    // ── In-memory user store (offline fallback) ───────────────────────────
    // Each entry: { email, password, fullName }
    static ArrayList<String[]> offlineUsers = new ArrayList<>();

    // ── App state ─────────────────────────────────────────────────────────
    static JFrame     frame;
    static CardLayout layout;
    static JPanel     container;

    static ArrayList<Product>  products     = new ArrayList<>();
    static ArrayList<CartItem> cart         = new ArrayList<>();
    static JButton             cartNavBtn   = null;
    static int                 loggedUserId = -1;
    static String              loggedName   = "Guest";

    // ── Palette ───────────────────────────────────────────────────────────
    static final Color BG           = new Color(15,  17,  26);
    static final Color SURFACE      = new Color(24,  27,  42);
    static final Color SURFACE2     = new Color(34,  38,  58);
    static final Color BORDER       = new Color(50,  55,  82);
    static final Color ACCENT       = new Color(99,  102, 241);
    static final Color ACCENT2      = new Color(139, 92,  246);
    static final Color SUCCESS      = new Color(16,  185, 129);
    static final Color SUCCESS2     = new Color(5,   150, 105);
    static final Color DANGER       = new Color(244, 63,  94);
    static final Color DANGER2      = new Color(200, 30,  60);
    static final Color WARNING      = new Color(251, 146, 60);
    static final Color TEXT_PRIMARY = new Color(241, 245, 249);
    static final Color TEXT_MUTED   = new Color(100, 116, 139);
    static final Color TEXT_DIM     = new Color(148, 163, 184);
    static final Color GOLD         = new Color(251, 191, 36);

    // ── Model ─────────────────────────────────────────────────────────────
    static class Product {
        int id, stock, price;
        String name, icon, category;
        Product(int id, String n, int p, String i, String c, int stock) {
            this.id=id; name=n; price=p; icon=i; category=c; this.stock=stock;
        }
        boolean inStock()  { return stock > 0; }
        boolean lowStock() { return stock > 0 && stock <= 5; }
    }

    static class CartItem {
        Product p; int qty = 1;
        CartItem(Product p) { this.p = p; }
    }

    // ════════════════════════════════════════════════════════════════════
    //  DATABASE LAYER
    // ════════════════════════════════════════════════════════════════════

    static boolean isDbAvailable() {
        if (dbChecked) return dbAvailable;
        dbChecked = true;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection cn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                dbAvailable = cn.isValid(2);
            }
        } catch (Exception e) {
            dbAvailable = false;
            System.out.println("[INFO] MySQL not available, running offline. Reason: " + e.getMessage());
        }
        return dbAvailable;
    }

    static Connection getConn() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    static void loadProductsFromDB() {
        products.clear();
        if (isDbAvailable()) {
            String sql = "SELECT p.id, p.name, p.icon, c.name AS category, p.price, p.stock_qty "
                       + "FROM products p JOIN categories c ON c.id = p.category_id "
                       + "WHERE p.is_active = 1 ORDER BY c.name, p.name";
            try (Connection cn = getConn();
                 Statement  st = cn.createStatement();
                 ResultSet  rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    products.add(new Product(
                        rs.getInt("id"), rs.getString("name"), rs.getInt("price"),
                        rs.getString("icon"), rs.getString("category"), rs.getInt("stock_qty")));
                }
                return;
            } catch (SQLException ex) {
                System.err.println("[DB] loadProducts: " + ex.getMessage());
            }
        }
        loadFallbackProducts();
    }

    static void loadFallbackProducts() {
        Object[][] d = {
            {1,"Whole Milk",52,"🥛","Dairy",50},
            {2,"Sourdough",38,"🍞","Bakery",30},
            {3,"Farm Eggs",75,"🥚","Dairy",40},
            {4,"Red Apple",110,"🍎","Fruits",60},
            {5,"Chips",45,"🍟","Snacks",80},
            {6,"Dark Choco",130,"🍫","Snacks",3},
            {7,"Orange Juice",85,"🧃","Drinks",35},
            {8,"Butter",65,"🧈","Dairy",0},
            {9,"Biscuits",38,"🍪","Snacks",90},
            {10,"Sparkling Water",25,"💧","Drinks",100}
        };
        for (Object[] row : d)
            products.add(new Product((int)row[0],(String)row[1],(int)row[2],
                                     (String)row[3],(String)row[4],(int)row[5]));
    }

    // ── REGISTER ─────────────────────────────────────────────────────────
    /** Returns null on success, or an error message string. */
    static String registerUser(String fullName, String email, String password) {
        if (fullName.trim().isEmpty()) return "Full name is required.";
        if (email.trim().isEmpty())    return "Email is required.";
        if (password.isEmpty())        return "Password is required.";
        if (!email.contains("@") || !email.contains(".")) return "Enter a valid email address.";
        if (password.length() < 4)     return "Password must be at least 4 characters.";

        if (isDbAvailable()) {
            try (Connection cn = getConn()) {
                // Check duplicate
                try (PreparedStatement chk = cn.prepareStatement(
                        "SELECT id FROM users WHERE email = ?")) {
                    chk.setString(1, email.trim().toLowerCase());
                    try (ResultSet rs = chk.executeQuery()) {
                        if (rs.next()) return "An account with this email already exists.";
                    }
                }
                // Insert
                try (PreparedStatement ins = cn.prepareStatement(
                        "INSERT INTO users (username, email, password_hash, full_name) VALUES (?,?,?,?)")) {
                    String uname = email.split("@")[0] + "_" + (System.currentTimeMillis() % 9999);
                    ins.setString(1, uname);
                    ins.setString(2, email.trim().toLowerCase());
                    ins.setString(3, password);          // use bcrypt in production
                    ins.setString(4, fullName.trim());
                    ins.executeUpdate();
                }
                return null;   // success
            } catch (SQLException ex) {
                return "Database error: " + ex.getMessage();
            }
        } else {
            // Offline mode — in-memory
            for (String[] u : offlineUsers)
                if (u[0].equalsIgnoreCase(email.trim()))
                    return "An account with this email already exists.";
            offlineUsers.add(new String[]{email.trim().toLowerCase(), password, fullName.trim()});
            return null;
        }
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────
    /** Returns user id > 0 on success, -1 on failure. Sets loggedName. */
    static int tryLogin(String email, String password) {
        if (email.trim().isEmpty() || password.isEmpty()) return -1;

        if (isDbAvailable()) {
            String sql = "SELECT id, full_name FROM users WHERE email = ? AND password_hash = ?";
            try (Connection cn = getConn();
                 PreparedStatement ps = cn.prepareStatement(sql)) {
                ps.setString(1, email.trim().toLowerCase());
                ps.setString(2, password);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        loggedName = rs.getString("full_name");
                        if (loggedName == null || loggedName.isEmpty()) loggedName = email;
                        return rs.getInt("id");
                    }
                }
            } catch (SQLException ex) {
                System.err.println("[DB] login failed: " + ex.getMessage());
                // fall through to offline check
            }
        }

        // Offline check (in-memory registered users + seeded demo)
        for (String[] u : offlineUsers) {
            if (u[0].equalsIgnoreCase(email.trim()) && u[1].equals(password)) {
                loggedName = u[2];
                return 1;
            }
        }
        return -1;
    }

    // ── ORDER PLACEMENT ───────────────────────────────────────────────────
    static String placeOrder(String paymentMethod) {
        if (!isDbAvailable()) {
            for (CartItem ci : cart)
                if (ci.p.stock < ci.qty) return ci.p.name + " has insufficient stock.";
            for (CartItem ci : cart) ci.p.stock -= ci.qty;
            return null;
        }
        try (Connection cn = getConn()) {
            cn.setAutoCommit(false);
            int orderId;
            try (PreparedStatement ps = cn.prepareStatement(
                    "INSERT INTO orders (user_id, total_amount, payment_method, status) VALUES (?,?,?,'paid')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, loggedUserId < 1 ? 1 : loggedUserId);
                ps.setBigDecimal(2, new BigDecimal(cartTotal()));
                ps.setString(3, paymentMethod);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { k.next(); orderId = k.getInt(1); }
            }
            try (CallableStatement cs = cn.prepareCall("{CALL sp_deduct_stock(?,?,?,?)}");
                 PreparedStatement ip = cn.prepareStatement(
                        "INSERT INTO order_items (order_id,product_id,quantity,unit_price) VALUES (?,?,?,?)")) {
                for (CartItem ci : cart) {
                    cs.setInt(1, ci.p.id); cs.setInt(2, ci.qty); cs.setInt(3, orderId);
                    cs.registerOutParameter(4, Types.TINYINT);
                    cs.execute();
                    if (cs.getInt(4) == 0) { cn.rollback(); return ci.p.name + " ran out of stock. Order cancelled."; }
                    ip.setInt(1, orderId); ip.setInt(2, ci.p.id);
                    ip.setInt(3, ci.qty);  ip.setBigDecimal(4, new BigDecimal(ci.p.price));
                    ip.executeUpdate();
                }
            }
            cn.commit(); return null;
        } catch (SQLException ex) { return "Database error: " + ex.getMessage(); }
    }

    static void refreshStock(Product p) {
        if (!isDbAvailable()) return;
        try (Connection cn = getConn();
             PreparedStatement ps = cn.prepareStatement("SELECT stock_qty FROM products WHERE id=?")) {
            ps.setInt(1, p.id);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) p.stock = rs.getInt(1); }
        } catch (SQLException ignored) {}
    }

    // ════════════════════════════════════════════════════════════════════
    //  MAIN
    // ════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        // Seed default offline account (always available, even without DB)
        offlineUsers.add(new String[]{"demo@quickmart.in", "demo123", "Demo User"});

        loadProductsFromDB();

        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
            catch (Exception ignored) {}

            frame = new JFrame("QuickMart");
            frame.setSize(980, 680);
            frame.setMinimumSize(new Dimension(800, 560));
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLocationRelativeTo(null);
            frame.getContentPane().setBackground(BG);

            layout    = new CardLayout();
            container = new JPanel(layout);
            container.setBackground(BG);

            container.add(loginPanel(),    "login");
            container.add(registerPanel(), "register");
            container.add(homePanel(),     "home");
            container.add(shopPanel(),     "shop");
            rebuildCart();

            frame.add(container);
            frame.setVisible(true);
            layout.show(container, "login");
        });
    }

    // ════════════════════════════════════════════════════════════════════
    //  COMPONENT HELPERS
    // ════════════════════════════════════════════════════════════════════

    static JButton gradientBtn(String text, Color c1, Color c2, boolean en) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isEnabled()) {
                    g2.setPaint(new GradientPaint(0,0,c1,getWidth(),getHeight(),c2));
                } else {
                    g2.setColor(SURFACE2);
                }
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),12,12));
                if (getModel().isRollover() && isEnabled()) {
                    g2.setColor(new Color(255,255,255,28));
                    g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),12,12));
                }
                g2.setColor(isEnabled() ? Color.WHITE : TEXT_MUTED);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()-fm.stringWidth(getText()))/2,
                    (getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
        };
        b.setEnabled(en);
        b.setOpaque(false); b.setContentAreaFilled(false);
        b.setBorderPainted(false); b.setFocusPainted(false);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    static JButton accentBtn(String t)  { return gradientBtn(t, ACCENT,  ACCENT2,  true);  }
    static JButton successBtn(String t) { return gradientBtn(t, SUCCESS, SUCCESS2, true);  }
    static JButton dangerBtn(String t)  { return gradientBtn(t, DANGER,  DANGER2,  false); }

    static JButton ghostBtn(String text) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isRollover()) {
                    g2.setColor(new Color(99,102,241,30));
                    g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),10,10));
                }
                g2.setColor(ACCENT); g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new RoundRectangle2D.Float(1,1,getWidth()-2,getHeight()-2,10,10));
                g2.setFont(getFont()); g2.setColor(ACCENT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()-fm.stringWidth(getText()))/2,
                    (getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
        };
        b.setOpaque(false); b.setContentAreaFilled(false);
        b.setBorderPainted(false); b.setFocusPainted(false);
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    static JButton roundIconBtn(String t, Color bg) {
        JButton b = new JButton(t) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? bg.brighter() : bg);
                g2.fillOval(0,0,getWidth(),getHeight());
                g2.setColor(Color.WHITE); g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()-fm.stringWidth(getText()))/2,
                    (getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
        };
        b.setOpaque(false); b.setContentAreaFilled(false);
        b.setBorderPainted(false); b.setFocusPainted(false);
        b.setFont(new Font("SansSerif", Font.BOLD, 15));
        b.setPreferredSize(new Dimension(30,30));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    static JTextField darkField(String placeholder) {
        JTextField tf = new JTextField() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(SURFACE2);
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),10,10));
                g2.setColor(isFocusOwner() ? ACCENT : BORDER);
                g2.setStroke(new BasicStroke(isFocusOwner() ? 1.8f : 1f));
                g2.draw(new RoundRectangle2D.Float(0,0,getWidth()-1,getHeight()-1,10,10));
                super.paintComponent(g); g2.dispose();
            }
        };
        tf.setOpaque(false); tf.setForeground(TEXT_PRIMARY);
        tf.setCaretColor(ACCENT);
        tf.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tf.setBorder(BorderFactory.createEmptyBorder(9,14,9,14));
        tf.setToolTipText(placeholder);
        return tf;
    }

    static JPasswordField darkPass(String placeholder) {
        JPasswordField pf = new JPasswordField() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(SURFACE2);
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),10,10));
                g2.setColor(isFocusOwner() ? ACCENT : BORDER);
                g2.setStroke(new BasicStroke(isFocusOwner() ? 1.8f : 1f));
                g2.draw(new RoundRectangle2D.Float(0,0,getWidth()-1,getHeight()-1,10,10));
                super.paintComponent(g); g2.dispose();
            }
        };
        pf.setOpaque(false); pf.setForeground(TEXT_PRIMARY);
        pf.setCaretColor(ACCENT);
        pf.setFont(new Font("SansSerif", Font.PLAIN, 13));
        pf.setBorder(BorderFactory.createEmptyBorder(9,14,9,14));
        pf.setToolTipText(placeholder);
        return pf;
    }

    static JPanel roundPanel(Color bg, int radius) {
        return new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0,0,0,60));
                g2.fill(new RoundRectangle2D.Float(4,4,getWidth()-3,getHeight()-3,radius,radius));
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth()-4,getHeight()-4,radius,radius));
                g2.setColor(BORDER); g2.setStroke(new BasicStroke(1));
                g2.draw(new RoundRectangle2D.Float(0,0,getWidth()-5,getHeight()-5,radius,radius));
                g2.dispose(); super.paintComponent(g);
            }
            @Override public boolean isOpaque(){return false;}
        };
    }

    static JLabel lbl(String t, int sz, int style, Color c) {
        JLabel l = new JLabel(t);
        l.setFont(new Font("SansSerif", style, sz));
        l.setForeground(c);
        return l;
    }

    static JLabel stockBadge(Product p) {
        String txt; Color col;
        if (!p.inStock())      { txt = "Out of Stock";              col = DANGER;   }
        else if (p.lowStock()) { txt = "Only " + p.stock + " left"; col = WARNING;  }
        else                   { txt = "In Stock · " + p.stock;     col = SUCCESS;  }
        return new JLabel(txt) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(col.getRed(),col.getGreen(),col.getBlue(),35));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
                g2.setColor(col); g2.setStroke(new BasicStroke(1));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);
                g2.setFont(getFont()); g2.setColor(col);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()-fm.stringWidth(getText()))/2,
                    (getHeight()+fm.getAscent()-fm.getDescent())/2);
                g2.dispose();
            }
            @Override public boolean isOpaque(){return false;}
        };
    }

    // ════════════════════════════════════════════════════════════════════
    //  SHARED BRANDING PANEL (left side on login / register)
    // ════════════════════════════════════════════════════════════════════

    static JPanel brandingPanel() {
        JPanel left = new JPanel(new GridBagLayout());
        left.setBackground(new Color(18, 20, 34));
        left.setPreferredSize(new Dimension(300, 0));
        left.setBorder(BorderFactory.createMatteBorder(0,0,0,1,BORDER));

        JPanel brand = new JPanel();
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setOpaque(false);

        JLabel bolt = lbl("⚡", 52, Font.PLAIN, GOLD);       bolt.setAlignmentX(0);
        JLabel name = lbl("QuickMart", 28, Font.BOLD, TEXT_PRIMARY); name.setAlignmentX(0);
        JLabel t1   = lbl("Fresh groceries.", 13, Font.PLAIN, TEXT_MUTED); t1.setAlignmentX(0);
        JLabel t2   = lbl("Delivered fast.",  13, Font.PLAIN, TEXT_DIM);   t2.setAlignmentX(0);

        brand.add(bolt); brand.add(Box.createVerticalStrut(12));
        brand.add(name); brand.add(Box.createVerticalStrut(8));
        brand.add(t1); brand.add(t2);
        brand.add(Box.createVerticalStrut(22));

        Color dbCol  = isDbAvailable() ? SUCCESS : WARNING;
        String dbTxt = isDbAvailable() ? "🟢 MySQL Connected" : "🟡 Offline Mode";
        JLabel dbLbl = lbl(dbTxt, 11, Font.BOLD, dbCol); dbLbl.setAlignmentX(0);
        brand.add(dbLbl); brand.add(Box.createVerticalStrut(18));

        for (String f : new String[]{"✓ Live Stock Tracking","✓ Instant Checkout","✓ Secure Auth"}) {
            JLabel fl = lbl(f, 12, Font.PLAIN, new Color(99,102,241,200));
            fl.setAlignmentX(0); brand.add(fl); brand.add(Box.createVerticalStrut(6));
        }
        left.add(brand);
        return left;
    }

    // ════════════════════════════════════════════════════════════════════
    //  NAV BAR
    // ════════════════════════════════════════════════════════════════════

    static JPanel navBar(String title, boolean showCart) {
        JPanel nav = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(SURFACE); g.fillRect(0,0,getWidth(),getHeight());
                g.setColor(BORDER);  g.fillRect(0,getHeight()-1,getWidth(),1);
            }
            @Override public boolean isOpaque(){return false;}
        };
        nav.setBorder(BorderFactory.createEmptyBorder(12,22,12,22));

        JPanel logo = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); logo.setOpaque(false);
        logo.add(lbl("⚡", 20, Font.PLAIN, GOLD));
        logo.add(lbl(" QuickMart", 18, Font.BOLD, TEXT_PRIMARY));
        nav.add(logo, BorderLayout.WEST);

        if (title != null && !title.isEmpty()) {
            JLabel tl = lbl(title, 13, Font.PLAIN, TEXT_MUTED);
            tl.setHorizontalAlignment(JLabel.CENTER);
            nav.add(tl, BorderLayout.CENTER);
        }

        if (showCart) {
            cartNavBtn = new JButton("🛒  Cart  " + cartItemCount()) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getModel().isRollover() ? SUCCESS.brighter() : SUCCESS);
                    g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),20,20));
                    g2.setColor(Color.WHITE); g2.setFont(getFont());
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(getText(),
                        (getWidth()-fm.stringWidth(getText()))/2,
                        (getHeight()+fm.getAscent()-fm.getDescent())/2);
                    g2.dispose();
                }
            };
            cartNavBtn.setOpaque(false); cartNavBtn.setContentAreaFilled(false);
            cartNavBtn.setBorderPainted(false); cartNavBtn.setFocusPainted(false);
            cartNavBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
            cartNavBtn.setPreferredSize(new Dimension(145,36));
            cartNavBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cartNavBtn.addActionListener(e -> showCart());
            nav.add(cartNavBtn, BorderLayout.EAST);
        }
        return nav;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CART HELPERS
    // ════════════════════════════════════════════════════════════════════

    static void addToCart(Product p) {
        refreshStock(p);
        if (!p.inStock()) {
            JOptionPane.showMessageDialog(frame, p.name+" is out of stock.",
                "Out of Stock", JOptionPane.WARNING_MESSAGE); return;
        }
        for (CartItem c : cart) {
            if (c.p == p) {
                if (c.qty+1 > p.stock) {
                    JOptionPane.showMessageDialog(frame,
                        "Only "+p.stock+" unit(s) of "+p.name+" available.",
                        "Stock Limit", JOptionPane.WARNING_MESSAGE); return;
                }
                c.qty++; updateCartNav(); rebuildCart(); return;
            }
        }
        cart.add(new CartItem(p)); updateCartNav(); rebuildCart();
    }

    static void updateCartNav() {
        if (cartNavBtn != null) cartNavBtn.setText("🛒  Cart  " + cartItemCount());
    }

    static int cartItemCount() { int n=0; for(CartItem c:cart) n+=c.qty; return n; }
    static int cartTotal()     { int t=0; for(CartItem c:cart) t+=c.p.price*c.qty; return t; }

    static void rebuildCart() {
        for (Component c : container.getComponents())
            if ("cart".equals(c.getName())) { container.remove(c); break; }
        JPanel cp = cartPanel(); cp.setName("cart");
        container.add(cp, "cart"); container.revalidate();
    }

    static void showCart()       { updateCartNav(); rebuildCart(); layout.show(container,"cart"); }
    static void refreshHomePanel(){ removeCard("home");  container.add(homePanel(),"home"); container.revalidate(); }
    static void refreshShopPanel(){ removeCard("shop");  JPanel sp=shopPanel(); sp.setName("shop"); container.add(sp,"shop"); container.revalidate(); }
    static void removeCard(String name) {
        for (Component c : container.getComponents()) if (name.equals(c.getName())) { container.remove(c); return; }
    }

    // ════════════════════════════════════════════════════════════════════
    //  LOGIN SCREEN
    // ════════════════════════════════════════════════════════════════════

    static JPanel loginPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG);
        outer.add(brandingPanel(), BorderLayout.WEST);

        JPanel right = new JPanel(new GridBagLayout());
        right.setBackground(BG);

        JPanel card = roundPanel(SURFACE, 20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(38,42,38,42));
        card.setPreferredSize(new Dimension(400, 490));

        JLabel title = lbl("Welcome back 👋", 22, Font.BOLD, TEXT_PRIMARY); title.setAlignmentX(0);
        JLabel sub   = lbl("Sign in to your account", 13, Font.PLAIN, TEXT_MUTED); sub.setAlignmentX(0);

        JLabel errorLbl = lbl("", 12, Font.BOLD, DANGER); errorLbl.setAlignmentX(0);

        JTextField   emailF = darkField("you@email.com");
        emailF.setAlignmentX(0); emailF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JPasswordField passF = darkPass("your password");
        passF.setAlignmentX(0); passF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JButton loginBtn = accentBtn("Sign In  →");
        loginBtn.setAlignmentX(0); loginBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));

        JSeparator sep = new JSeparator(); sep.setForeground(BORDER);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        JLabel noAcct = lbl("Don't have an account?", 12, Font.PLAIN, TEXT_MUTED); noAcct.setAlignmentX(0);

        JButton goReg = ghostBtn("Create Account  →");
        goReg.setAlignmentX(0); goReg.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JLabel hint = lbl("Default: demo@quickmart.in  /  demo123", 10, Font.PLAIN, TEXT_MUTED); hint.setAlignmentX(0);

        ActionListener doLogin = e -> {
            errorLbl.setText("");
            String em = emailF.getText().trim();
            String pw = new String(passF.getPassword());
            if (em.isEmpty() || pw.isEmpty()) { errorLbl.setText("✗  Please fill in both fields."); return; }
            int uid = tryLogin(em, pw);
            if (uid > 0) {
                loggedUserId = uid;
                loadProductsFromDB();
                refreshShopPanel(); refreshHomePanel();
                layout.show(container, "home");
            } else {
                errorLbl.setText("✗  Incorrect email or password.");
            }
        };
        loginBtn.addActionListener(doLogin);
        passF.addActionListener(doLogin);
        goReg.addActionListener(e -> layout.show(container, "register"));

        card.add(title);    card.add(Box.createVerticalStrut(4));
        card.add(sub);      card.add(Box.createVerticalStrut(22));
        card.add(errorLbl); card.add(Box.createVerticalStrut(4));
        card.add(lbl("Email", 11, Font.BOLD, TEXT_DIM)); card.add(Box.createVerticalStrut(5));
        card.add(emailF);   card.add(Box.createVerticalStrut(14));
        card.add(lbl("Password", 11, Font.BOLD, TEXT_DIM)); card.add(Box.createVerticalStrut(5));
        card.add(passF);    card.add(Box.createVerticalStrut(24));
        card.add(loginBtn); card.add(Box.createVerticalStrut(22));
        card.add(sep);      card.add(Box.createVerticalStrut(16));
        card.add(noAcct);   card.add(Box.createVerticalStrut(8));
        card.add(goReg);    card.add(Box.createVerticalStrut(18));
        card.add(hint);

        right.add(card);
        outer.add(right, BorderLayout.CENTER);
        return outer;
    }

    // ════════════════════════════════════════════════════════════════════
    //  REGISTER SCREEN
    // ════════════════════════════════════════════════════════════════════

    static JPanel registerPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG);
        outer.add(brandingPanel(), BorderLayout.WEST);

        JPanel right = new JPanel(new GridBagLayout());
        right.setBackground(BG);

        JPanel card = roundPanel(SURFACE, 20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(34,42,34,42));
        card.setPreferredSize(new Dimension(400, 560));

        JLabel title  = lbl("Create account ✨", 22, Font.BOLD, TEXT_PRIMARY); title.setAlignmentX(0);
        JLabel sub    = lbl("Start shopping in seconds", 13, Font.PLAIN, TEXT_MUTED); sub.setAlignmentX(0);

        JLabel errorLbl   = lbl("", 12, Font.BOLD, DANGER);  errorLbl.setAlignmentX(0);
        JLabel successLbl = lbl("", 12, Font.BOLD, SUCCESS); successLbl.setAlignmentX(0);

        JTextField   nameF  = darkField("Full Name");
        nameF.setAlignmentX(0); nameF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JTextField   emailF = darkField("you@email.com");
        emailF.setAlignmentX(0); emailF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JPasswordField passF    = darkPass("Password (min 4 chars)");
        passF.setAlignmentX(0); passF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JPasswordField confirmF = darkPass("Confirm password");
        confirmF.setAlignmentX(0); confirmF.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JButton regBtn = successBtn("Create Account  →");
        regBtn.setAlignmentX(0); regBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));

        JSeparator sep = new JSeparator(); sep.setForeground(BORDER);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        JButton goLogin = ghostBtn("Already have an account? Sign In");
        goLogin.setAlignmentX(0); goLogin.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        ActionListener doRegister = e -> {
            errorLbl.setText(""); successLbl.setText("");
            String fn   = nameF.getText().trim();
            String em   = emailF.getText().trim();
            String pw   = new String(passF.getPassword());
            String cpw  = new String(confirmF.getPassword());

            if (!pw.equals(cpw)) { errorLbl.setText("✗  Passwords do not match."); return; }

            String err = registerUser(fn, em, pw);
            if (err != null) {
                errorLbl.setText("✗  " + err);
            } else {
                successLbl.setText("✅  Account created! Signing you in…");
                regBtn.setEnabled(false);
                new javax.swing.Timer(900, ev -> {
                    int uid = tryLogin(em, pw);
                    if (uid > 0) {
                        loggedUserId = uid;
                        loadProductsFromDB();
                        refreshShopPanel(); refreshHomePanel();
                        layout.show(container, "home");
                    }
                    regBtn.setEnabled(true);
                }) {{ setRepeats(false); }}.start();
            }
        };
        regBtn.addActionListener(doRegister);
        confirmF.addActionListener(doRegister);
        goLogin.addActionListener(e -> layout.show(container, "login"));

        card.add(title);    card.add(Box.createVerticalStrut(4));
        card.add(sub);      card.add(Box.createVerticalStrut(14));
        card.add(errorLbl); card.add(Box.createVerticalStrut(2));
        card.add(successLbl); card.add(Box.createVerticalStrut(4));
        card.add(lbl("Full Name", 11, Font.BOLD, TEXT_DIM));     card.add(Box.createVerticalStrut(5));
        card.add(nameF);    card.add(Box.createVerticalStrut(12));
        card.add(lbl("Email Address", 11, Font.BOLD, TEXT_DIM)); card.add(Box.createVerticalStrut(5));
        card.add(emailF);   card.add(Box.createVerticalStrut(12));
        card.add(lbl("Password", 11, Font.BOLD, TEXT_DIM));      card.add(Box.createVerticalStrut(5));
        card.add(passF);    card.add(Box.createVerticalStrut(12));
        card.add(lbl("Confirm Password", 11, Font.BOLD, TEXT_DIM)); card.add(Box.createVerticalStrut(5));
        card.add(confirmF); card.add(Box.createVerticalStrut(22));
        card.add(regBtn);   card.add(Box.createVerticalStrut(18));
        card.add(sep);      card.add(Box.createVerticalStrut(14));
        card.add(goLogin);

        right.add(card);
        outer.add(right, BorderLayout.CENTER);
        return outer;
    }

    // ════════════════════════════════════════════════════════════════════
    //  HOME SCREEN
    // ════════════════════════════════════════════════════════════════════

    static JPanel homePanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setName("home"); p.setBackground(BG);
        p.add(navBar(null, false), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout()); center.setBackground(BG);
        JPanel inner  = new JPanel(); inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS)); inner.setOpaque(false);

        String first = loggedName.split(" ")[0];
        JLabel greet = lbl("Hello, " + first + "! 🌿", 28, Font.BOLD, TEXT_PRIMARY);
        greet.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub = lbl("Fresh groceries, one tap away.", 14, Font.PLAIN, TEXT_MUTED);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel tiles = new JPanel(new GridLayout(1, 3, 18, 0));
        tiles.setOpaque(false);
        tiles.setMaximumSize(new Dimension(700, 135));
        tiles.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel shopTile = homeTile("🛍","Browse Shop","Explore fresh items", ACCENT);
        JPanel cartTile = homeTile("🛒","My Cart",    "View & checkout",      SUCCESS);
        JPanel syncTile = homeTile("🔄","Sync Stock", "Refresh from database",new Color(6,182,212));

        shopTile.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){layout.show(container,"shop");}});
        cartTile.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){showCart();}});
        syncTile.addMouseListener(new MouseAdapter(){
            public void mouseClicked(MouseEvent e){
                dbChecked = false;
                loadProductsFromDB(); refreshShopPanel();
                JOptionPane.showMessageDialog(frame,
                    isDbAvailable() ? "Stock refreshed from MySQL ✓" : "Offline — demo stock reloaded.",
                    "Sync", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        tiles.add(shopTile); tiles.add(cartTile); tiles.add(syncTile);

        JLabel modeLbl = lbl(
            isDbAvailable() ? "🟢 MySQL Connected" : "🟡 Offline Mode — data stored in memory",
            11, Font.PLAIN, isDbAvailable() ? SUCCESS : WARNING);
        modeLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton logout = ghostBtn("Sign Out");
        logout.setAlignmentX(Component.CENTER_ALIGNMENT);
        logout.addActionListener(e -> {
            loggedUserId = -1; loggedName = "Guest"; cart.clear(); updateCartNav();
            layout.show(container, "login");
        });

        inner.add(greet);   inner.add(Box.createVerticalStrut(6));
        inner.add(sub);     inner.add(Box.createVerticalStrut(32));
        inner.add(tiles);   inner.add(Box.createVerticalStrut(24));
        inner.add(modeLbl); inner.add(Box.createVerticalStrut(16));
        inner.add(logout);

        center.add(inner); p.add(center, BorderLayout.CENTER);
        return p;
    }

    static JPanel homeTile(String icon, String title, String sub, Color accent) {
        JPanel tile = new JPanel(new BorderLayout()) {
            boolean hover = false;
            { addMouseListener(new MouseAdapter(){
                public void mouseEntered(MouseEvent e){hover=true;repaint();}
                public void mouseExited(MouseEvent e){hover=false;repaint();}
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0,0,0,55));
                g2.fill(new RoundRectangle2D.Float(4,4,getWidth()-3,getHeight()-3,18,18));
                g2.setColor(hover ? SURFACE2 : SURFACE);
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth()-3,getHeight()-3,18,18));
                g2.setPaint(new GradientPaint(0,0,accent,getWidth(),0,accent.darker()));
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth()-3,7,6,6));
                g2.setColor(hover ? accent : BORDER);
                g2.setStroke(new BasicStroke(hover ? 1.8f : 1f));
                g2.draw(new RoundRectangle2D.Float(0,0,getWidth()-4,getHeight()-4,18,18));
                g2.dispose(); super.paintComponent(g);
            }
            @Override public boolean isOpaque(){return false;}
        };
        tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tile.setBorder(BorderFactory.createEmptyBorder(20,18,18,18));
        JPanel text = new JPanel(); text.setLayout(new BoxLayout(text,BoxLayout.Y_AXIS)); text.setOpaque(false);
        text.add(lbl(icon, 30, Font.PLAIN, TEXT_PRIMARY));
        text.add(Box.createVerticalStrut(10));
        text.add(lbl(title, 14, Font.BOLD, TEXT_PRIMARY));
        text.add(Box.createVerticalStrut(3));
        text.add(lbl(sub, 11, Font.PLAIN, TEXT_MUTED));
        tile.add(text, BorderLayout.CENTER);
        return tile;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SHOP SCREEN
    // ════════════════════════════════════════════════════════════════════

    static JPanel shopPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setName("shop"); p.setBackground(BG);
        p.add(navBar("Shop", true), BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, 4, 14, 14));
        grid.setBackground(BG);
        grid.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        for (Product pr : products) grid.add(productCard(pr));

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(null); scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        p.add(scroll, BorderLayout.CENTER);

        JPanel bar = new JPanel(new BorderLayout(10,0));
        bar.setBackground(SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1,0,0,0,BORDER),
            BorderFactory.createEmptyBorder(8,16,8,16)));
        JButton back = ghostBtn("← Home");
        back.addActionListener(e -> layout.show(container,"home"));
        long oos = products.stream().filter(pr -> !pr.inStock()).count();
        long low = products.stream().filter(Product::lowStock).count();
        JLabel stat = lbl(products.size()+" products · "+oos+" out of stock · "+low+" low stock", 11, Font.PLAIN, TEXT_MUTED);
        bar.add(back, BorderLayout.WEST); bar.add(stat, BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    static JPanel productCard(Product pr) {
        JPanel card = new JPanel(new BorderLayout(0,6)) {
            boolean hover = false;
            { addMouseListener(new MouseAdapter(){
                public void mouseEntered(MouseEvent e){hover=true;repaint();}
                public void mouseExited(MouseEvent e){hover=false;repaint();}
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0,0,0,60));
                g2.fill(new RoundRectangle2D.Float(3,3,getWidth()-2,getHeight()-2,16,16));
                g2.setColor(hover ? SURFACE2 : SURFACE);
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth()-3,getHeight()-3,16,16));
                Color bc = !pr.inStock() ? new Color(244,63,94,100)
                         : pr.lowStock() ? new Color(251,146,60,120)
                         : hover ? ACCENT : BORDER;
                g2.setColor(bc); g2.setStroke(new BasicStroke(!pr.inStock()||hover?1.8f:1f));
                g2.draw(new RoundRectangle2D.Float(0,0,getWidth()-4,getHeight()-4,16,16));
                g2.dispose(); super.paintComponent(g);
            }
            @Override public boolean isOpaque(){return false;}
        };
        card.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));

        JLabel cat   = lbl(pr.category, 10, Font.BOLD, TEXT_MUTED);
        JLabel ico   = lbl(pr.icon, 34, Font.PLAIN, TEXT_PRIMARY); ico.setHorizontalAlignment(JLabel.CENTER);
        JLabel nameL = lbl(pr.name, 13, Font.BOLD, pr.inStock()?TEXT_PRIMARY:TEXT_MUTED); nameL.setHorizontalAlignment(JLabel.CENTER);
        JLabel priceL= lbl("₹ "+pr.price, 14, Font.BOLD, GOLD); priceL.setHorizontalAlignment(JLabel.CENTER);
        JLabel badge = stockBadge(pr); badge.setHorizontalAlignment(JLabel.CENTER);
        badge.setFont(new Font("SansSerif", Font.BOLD, 10));

        JPanel info = new JPanel(new GridLayout(4,1,2,3)); info.setOpaque(false);
        info.add(ico); info.add(nameL); info.add(priceL); info.add(badge);

        JButton addBtn;
        if (!pr.inStock()) {
            addBtn = dangerBtn("Out of Stock");
        } else {
            addBtn = accentBtn("+ Add");
            addBtn.addActionListener(e -> {
                addToCart(pr);
                addBtn.setText("✓ Added"); addBtn.setEnabled(false);
                new javax.swing.Timer(900, ev -> {
                    addBtn.setText("+ Add"); addBtn.setEnabled(true);
                }) {{ setRepeats(false); }}.start();
            });
        }
        addBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        addBtn.setPreferredSize(new Dimension(0,34));

        card.add(cat,    BorderLayout.NORTH);
        card.add(info,   BorderLayout.CENTER);
        card.add(addBtn, BorderLayout.SOUTH);
        return card;
    }

    // ════════════════════════════════════════════════════════════════════
    //  CART SCREEN
    // ════════════════════════════════════════════════════════════════════

    static JPanel cartPanel() {
        JPanel main = new JPanel(new BorderLayout()); main.setBackground(BG);
        main.add(navBar("My Cart", false), BorderLayout.NORTH);

        if (cart.isEmpty()) {
            JPanel empty = new JPanel(new GridBagLayout()); empty.setBackground(BG);
            JPanel box = new JPanel(); box.setLayout(new BoxLayout(box,BoxLayout.Y_AXIS)); box.setOpaque(false);
            JLabel ico = lbl("🛒",50,Font.PLAIN,TEXT_MUTED); ico.setAlignmentX(Component.CENTER_ALIGNMENT);
            JLabel msg = lbl("Your cart is empty",18,Font.BOLD,TEXT_DIM); msg.setAlignmentX(Component.CENTER_ALIGNMENT);
            JLabel sub = lbl("Add some items from the shop.",13,Font.PLAIN,TEXT_MUTED); sub.setAlignmentX(Component.CENTER_ALIGNMENT);
            JButton gs = accentBtn("  Browse Shop  "); gs.setAlignmentX(Component.CENTER_ALIGNMENT);
            gs.addActionListener(e -> layout.show(container,"shop"));
            box.add(ico); box.add(Box.createVerticalStrut(14)); box.add(msg);
            box.add(Box.createVerticalStrut(6)); box.add(sub); box.add(Box.createVerticalStrut(22)); box.add(gs);
            empty.add(box); main.add(empty, BorderLayout.CENTER);
        } else {
            JPanel list = new JPanel(); list.setLayout(new BoxLayout(list,BoxLayout.Y_AXIS));
            list.setBackground(BG); list.setBorder(BorderFactory.createEmptyBorder(16,20,16,20));
            for (CartItem c : new ArrayList<>(cart)) { list.add(cartRow(c)); list.add(Box.createVerticalStrut(10)); }
            JScrollPane scroll = new JScrollPane(list);
            scroll.setBorder(null); scroll.setBackground(BG); scroll.getViewport().setBackground(BG);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            main.add(scroll, BorderLayout.CENTER);
        }

        int total = cartTotal();
        JPanel bar = new JPanel(new BorderLayout(16,0)) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(SURFACE); g.fillRect(0,0,getWidth(),getHeight());
                g.setColor(BORDER);  g.fillRect(0,0,getWidth(),1); super.paintComponent(g);
            }
            @Override public boolean isOpaque(){return false;}
        };
        bar.setBorder(BorderFactory.createEmptyBorder(14,22,14,22));

        JPanel totals = new JPanel(); totals.setLayout(new BoxLayout(totals,BoxLayout.Y_AXIS)); totals.setOpaque(false);
        totals.add(lbl(cartItemCount()+" item"+(cartItemCount()==1?"":"s")+" in cart",12,Font.PLAIN,TEXT_MUTED));
        totals.add(Box.createVerticalStrut(3));
        totals.add(lbl("₹ "+total, 24, Font.BOLD, TEXT_PRIMARY));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT,10,0)); btns.setOpaque(false);
        JButton back = ghostBtn("← Shop");
        JButton checkout = accentBtn("Checkout  →");
        checkout.setPreferredSize(new Dimension(160,40));
        checkout.setEnabled(!cart.isEmpty());
        back.addActionListener(e -> layout.show(container,"shop"));
        checkout.addActionListener(e -> {
            removeCard("payment");
            JPanel pay = paymentPanel(total); pay.setName("payment");
            container.add(pay,"payment"); layout.show(container,"payment");
        });
        btns.add(back); btns.add(checkout);
        bar.add(totals, BorderLayout.WEST); bar.add(btns, BorderLayout.EAST);
        main.add(bar, BorderLayout.SOUTH);
        return main;
    }

    static JPanel cartRow(CartItem c) {
        JPanel row = new JPanel(new BorderLayout(14,0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0,0,0,45));
                g2.fill(new RoundRectangle2D.Float(3,3,getWidth()-2,getHeight()-2,14,14));
                g2.setColor(SURFACE);
                g2.fill(new RoundRectangle2D.Float(0,0,getWidth()-3,getHeight()-3,14,14));
                g2.setColor(BORDER); g2.setStroke(new BasicStroke(1));
                g2.draw(new RoundRectangle2D.Float(0,0,getWidth()-4,getHeight()-4,14,14));
                g2.dispose(); super.paintComponent(g);
            }
            @Override public boolean isOpaque(){return false;}
        };
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        row.setBorder(BorderFactory.createEmptyBorder(12,16,12,16));

        JLabel ico = lbl(c.p.icon, 26, Font.PLAIN, TEXT_PRIMARY);
        JPanel info = new JPanel(); info.setLayout(new BoxLayout(info,BoxLayout.Y_AXIS)); info.setOpaque(false);
        info.add(lbl(c.p.name, 14, Font.BOLD, TEXT_PRIMARY));
        info.add(Box.createVerticalStrut(3));
        String stk = c.p.stock < c.qty ? "  ⚠ only "+c.p.stock+" in stock" : "";
        info.add(lbl("₹"+c.p.price+" each"+stk, 12, Font.PLAIN, c.p.stock<c.qty?WARNING:TEXT_MUTED));

        JButton minus = roundIconBtn("−", DANGER);
        JLabel  qtyL  = lbl(""+c.qty, 14, Font.BOLD, TEXT_PRIMARY);
        qtyL.setHorizontalAlignment(JLabel.CENTER); qtyL.setPreferredSize(new Dimension(30,30));
        JButton plus  = roundIconBtn("+", SUCCESS);
        JLabel  total = lbl("₹"+(c.p.price*c.qty), 14, Font.BOLD, GOLD);
        total.setPreferredSize(new Dimension(70,30)); total.setHorizontalAlignment(JLabel.RIGHT);

        minus.addActionListener(e -> { c.qty--; if(c.qty<=0) cart.remove(c); showCart(); });
        plus.addActionListener(e  -> {
            if (c.qty+1 > c.p.stock) JOptionPane.showMessageDialog(frame,
                "Only "+c.p.stock+" unit(s) available.","Stock Limit",JOptionPane.WARNING_MESSAGE);
            else { c.qty++; showCart(); }
        });

        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0)); ctrl.setOpaque(false);
        ctrl.add(minus); ctrl.add(qtyL); ctrl.add(plus);
        ctrl.add(Box.createHorizontalStrut(10)); ctrl.add(total);

        row.add(ico, BorderLayout.WEST); row.add(info, BorderLayout.CENTER); row.add(ctrl, BorderLayout.EAST);
        return row;
    }

    // ════════════════════════════════════════════════════════════════════
    //  PAYMENT SCREEN
    // ════════════════════════════════════════════════════════════════════

    static JPanel paymentPanel(int total) {
        JPanel p = new JPanel(new BorderLayout()); p.setBackground(BG);
        p.add(navBar("Checkout", false), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout()); center.setBackground(BG);

        JPanel card = roundPanel(SURFACE, 20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(34,38,34,38));
        card.setPreferredSize(new Dimension(440, 490));

        JLabel title = lbl("Complete your order", 20, Font.BOLD, TEXT_PRIMARY); title.setAlignmentX(0);

        JPanel summary = roundPanel(SURFACE2, 10);
        summary.setLayout(new BorderLayout());
        summary.setBorder(BorderFactory.createEmptyBorder(12,16,12,16));
        summary.add(lbl("Order Total  ("+cartItemCount()+" items)", 13, Font.PLAIN, TEXT_MUTED), BorderLayout.WEST);
        summary.add(lbl("₹ "+total, 17, Font.BOLD, GOLD), BorderLayout.EAST);
        summary.setAlignmentX(0); summary.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        JLabel mLbl = lbl("Payment method", 11, Font.BOLD, TEXT_DIM); mLbl.setAlignmentX(0);

        String[][] methods = {{"📱","UPI"},{"💳","Card"},{"💵","Cash"},{"👛","Wallet"}};
        ButtonGroup group = new ButtonGroup();
        JToggleButton[] toggles = new JToggleButton[4];
        JPanel methodGrid = new JPanel(new GridLayout(1,4,10,0));
        methodGrid.setOpaque(false); methodGrid.setAlignmentX(0);
        methodGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));

        for (int i=0; i<methods.length; i++) {
            final String mn = methods[i][1];
            JToggleButton tb = new JToggleButton("<html><center>"+methods[i][0]+"<br><small>"+mn+"</small></center></html>") {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(isSelected()?new Color(99,102,241,55):SURFACE2);
                    g2.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),10,10));
                    g2.setColor(isSelected()?ACCENT:BORDER);
                    g2.setStroke(new BasicStroke(isSelected()?2f:1f));
                    g2.draw(new RoundRectangle2D.Float(0,0,getWidth()-1,getHeight()-1,10,10));
                    super.paintComponent(g); g2.dispose();
                }
            };
            tb.setOpaque(false); tb.setContentAreaFilled(false);
            tb.setBorderPainted(false); tb.setFocusPainted(false);
            tb.setForeground(TEXT_PRIMARY); tb.setFont(new Font("SansSerif",Font.PLAIN,12));
            tb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            group.add(tb); methodGrid.add(tb); toggles[i] = tb;
            if (mn.equals("UPI")) tb.setSelected(true);
        }

        JTextField details = darkField("UPI ID / card number (optional)");
        details.setAlignmentX(0); details.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel errorLbl = lbl("", 12, Font.BOLD, DANGER); errorLbl.setAlignmentX(0);

        JButton payBtn  = successBtn("Pay  ₹"+total+"  →");
        JButton backBtn = ghostBtn("← Back to Cart");
        payBtn.setAlignmentX(0);  payBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        backBtn.setAlignmentX(0); backBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        payBtn.addActionListener(e -> {
            String sel = "UPI";
            for (int i=0; i<methods.length; i++) if (toggles[i].isSelected()) { sel = methods[i][1]; break; }
            payBtn.setEnabled(false); payBtn.setText("Processing…");
            final String method = sel;
            new SwingWorker<String,Void>() {
                @Override protected String doInBackground() { return placeOrder(method); }
                @Override protected void done() {
                    try {
                        String err = get();
                        if (err == null) {
                            cart.clear(); updateCartNav(); rebuildCart();
                            loadProductsFromDB(); refreshShopPanel();
                            showSuccessDialog(); layout.show(container,"home");
                        } else {
                            errorLbl.setText("✗  "+err);
                            payBtn.setEnabled(true); payBtn.setText("Pay  ₹"+total+"  →");
                        }
                    } catch (Exception ex) {
                        errorLbl.setText("✗  Unexpected error.");
                        payBtn.setEnabled(true); payBtn.setText("Pay  ₹"+total+"  →");
                    }
                }
            }.execute();
        });
        backBtn.addActionListener(e -> showCart());

        card.add(title);    card.add(Box.createVerticalStrut(18));
        card.add(summary);  card.add(Box.createVerticalStrut(22));
        card.add(mLbl);     card.add(Box.createVerticalStrut(10));
        card.add(methodGrid); card.add(Box.createVerticalStrut(18));
        card.add(lbl("Details (optional)", 11, Font.BOLD, TEXT_DIM)); card.add(Box.createVerticalStrut(6));
        card.add(details);  card.add(Box.createVerticalStrut(12));
        card.add(errorLbl); card.add(Box.createVerticalStrut(8));
        card.add(payBtn);   card.add(Box.createVerticalStrut(10));
        card.add(backBtn);

        center.add(card); p.add(center, BorderLayout.CENTER);
        return p;
    }

    static void showSuccessDialog() {
        JDialog d = new JDialog(frame, true);
        d.setUndecorated(true); d.setSize(390, 260); d.setLocationRelativeTo(frame);
        JPanel panel = roundPanel(SURFACE, 20);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(34,40,34,40));
        JLabel tick = lbl("✅", 40, Font.PLAIN, SUCCESS); tick.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel msg  = lbl("Payment Successful!", 19, Font.BOLD, TEXT_PRIMARY); msg.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub  = lbl("Stock updated · Thank you! 🎉", 12, Font.PLAIN, TEXT_MUTED); sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton ok  = accentBtn("  Continue Shopping  "); ok.setAlignmentX(Component.CENTER_ALIGNMENT);
        ok.addActionListener(e -> d.dispose());
        panel.add(tick); panel.add(Box.createVerticalStrut(12));
        panel.add(msg);  panel.add(Box.createVerticalStrut(7));
        panel.add(sub);  panel.add(Box.createVerticalStrut(22)); panel.add(ok);
        d.setContentPane(panel); d.setVisible(true);
    }
}