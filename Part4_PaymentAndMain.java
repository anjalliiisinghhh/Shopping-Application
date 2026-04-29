import java.awt.*;
import java.awt.geom.*;
import javax.swing.*;

// ════════════════════════════════════════════════════════════════════════════
//  PART 4 — PAYMENT SCREEN  +  APPLICATION ENTRY POINT  (QuickMartApp)
//
//  Contains:
//    • Part4_Payment.paymentPanel()  — checkout form with method selector
//    • Part4_Payment.showSuccessDialog() — modal success confirmation
//    • QuickMartApp                  — main class: bootstraps the JFrame,
//                                      wires CardLayout, and exposes shared
//                                      helpers (showCart, rebuildCart, etc.)
//
//  ── HOW THE 4 FILES RELATE ───────────────────────────────────────────────
//  Part1  → data models + DB layer  (Product, CartItem, all SQL calls)
//  Part2  → colour palette + widget factories (buttons, fields, navBar…)
//  Part3  → five screens  (Login, Register, Home, Shop, Cart)
//  Part4  → Payment screen + QuickMartApp main entry point
//
//  ── COMPILE & RUN ────────────────────────────────────────────────────────
//  Windows :  javac -cp .;mysql-connector-j-8.x.jar *.java
//             java  -cp .;mysql-connector-j-8.x.jar QuickMartApp
//  Mac/Linux: javac -cp .:mysql-connector-j-8.x.jar *.java
//             java  -cp .:mysql-connector-j-8.x.jar QuickMartApp
// ════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
//  PAYMENT SCREEN
// ─────────────────────────────────────────────────────────────────────────────
class Part4_Payment {

    // Colour aliases for readability
    static final Color BG           = Part2_UIComponents.BG;
    static final Color SURFACE      = Part2_UIComponents.SURFACE;
    static final Color SURFACE2     = Part2_UIComponents.SURFACE2;
    static final Color BORDER       = Part2_UIComponents.BORDER;
    static final Color ACCENT       = Part2_UIComponents.ACCENT;
    static final Color SUCCESS      = Part2_UIComponents.SUCCESS;
    static final Color DANGER       = Part2_UIComponents.DANGER;
    static final Color TEXT_PRIMARY = Part2_UIComponents.TEXT_PRIMARY;
    static final Color TEXT_MUTED   = Part2_UIComponents.TEXT_MUTED;
    static final Color TEXT_DIM     = Part2_UIComponents.TEXT_DIM;

    // ════════════════════════════════════════════════════════════════════
    //  PAYMENT PANEL
    // ════════════════════════════════════════════════════════════════════

    /**
     * Builds the checkout / payment screen.
     *
     * Features:
     *  • Order summary pill (total + item count)
     *  • Payment method toggle: UPI / Card / Cash / Wallet
     *  • Optional details text field
     *  • "Pay" button — runs {@link Part1_DatabaseAndModel#placeOrder} on a
     *    background thread so the UI never freezes
     *  • Inline error label (shown on DB failure or stock conflict)
     *  • On success: clears cart, refreshes stock, shows success dialog
     *
     * @param total  pre-calculated cart total in rupees
     */
    static JPanel paymentPanel(int total) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.add(Part2_UIComponents.navBar("Checkout", false), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(BG);

        // ── Card container ────────────────────────────────────────────
        JPanel card = Part2_UIComponents.roundPanel(SURFACE, 20);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(34, 38, 34, 38));
        card.setPreferredSize(new Dimension(440, 490));

        JLabel title = Part2_UIComponents.lbl(
            "Complete your order", 20, Font.BOLD, TEXT_PRIMARY);
        title.setAlignmentX(0);

        // ── Order summary row ─────────────────────────────────────────
        JPanel summary = Part2_UIComponents.roundPanel(SURFACE2, 10);
        summary.setLayout(new BorderLayout());
        summary.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        summary.add(Part2_UIComponents.lbl(
            "Order Total  (" + QuickMartApp.cartItemCount() + " items)",
            13, Font.PLAIN, TEXT_MUTED), BorderLayout.WEST);
        summary.add(Part2_UIComponents.lbl(
            "₹ " + total, 17, Font.BOLD, Part2_UIComponents.GOLD), BorderLayout.EAST);
        summary.setAlignmentX(0);
        summary.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        // ── Payment method toggle group ───────────────────────────────
        JLabel mLbl = Part2_UIComponents.lbl(
            "Payment method", 11, Font.BOLD, TEXT_DIM);
        mLbl.setAlignmentX(0);

        String[][] methods = {
            {"📱", "UPI"},
            {"💳", "Card"},
            {"💵", "Cash"},
            {"👛", "Wallet"}
        };
        ButtonGroup     group      = new ButtonGroup();
        JToggleButton[] toggles    = new JToggleButton[4];
        JPanel          methodGrid = new JPanel(new GridLayout(1, 4, 10, 0));
        methodGrid.setOpaque(false);
        methodGrid.setAlignmentX(0);
        methodGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));

        for (int i = 0; i < methods.length; i++) {
            final String mn = methods[i][1];
            JToggleButton tb = new JToggleButton(
                "<html><center>" + methods[i][0] + "<br><small>" + mn + "</small></center></html>") {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                        RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(isSelected()
                        ? new Color(99, 102, 241, 55) : SURFACE2);
                    g2.fill(new RoundRectangle2D.Float(
                        0, 0, getWidth(), getHeight(), 10, 10));
                    g2.setColor(isSelected() ? ACCENT : BORDER);
                    g2.setStroke(new BasicStroke(isSelected() ? 2f : 1f));
                    g2.draw(new RoundRectangle2D.Float(
                        0, 0, getWidth()-1, getHeight()-1, 10, 10));
                    super.paintComponent(g);
                    g2.dispose();
                }
            };
            tb.setOpaque(false); tb.setContentAreaFilled(false);
            tb.setBorderPainted(false); tb.setFocusPainted(false);
            tb.setForeground(TEXT_PRIMARY);
            tb.setFont(new Font("SansSerif", Font.PLAIN, 12));
            tb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            group.add(tb);
            methodGrid.add(tb);
            toggles[i] = tb;
            if (mn.equals("UPI")) tb.setSelected(true);
        }

        // ── Detail field (optional) ───────────────────────────────────
        JTextField details = Part2_UIComponents.darkField(
            "UPI ID / card number (optional)");
        details.setAlignmentX(0);
        details.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        // ── Error label ───────────────────────────────────────────────
        JLabel errorLbl = Part2_UIComponents.lbl("", 12, Font.BOLD, DANGER);
        errorLbl.setAlignmentX(0);

        // ── Action buttons ────────────────────────────────────────────
        JButton payBtn  = Part2_UIComponents.successBtn("Pay  ₹" + total + "  →");
        JButton backBtn = Part2_UIComponents.ghostBtn("← Back to Cart");
        payBtn.setAlignmentX(0);  payBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        backBtn.setAlignmentX(0); backBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        payBtn.addActionListener(e -> {
            // Determine selected method
            String sel = "UPI";
            for (int i = 0; i < methods.length; i++)
                if (toggles[i].isSelected()) { sel = methods[i][1]; break; }

            payBtn.setEnabled(false);
            payBtn.setText("Processing…");
            final String method = sel;

            // Run DB work off the Event Dispatch Thread
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() {
                    return Part1_DatabaseAndModel.placeOrder(method);
                }
                @Override protected void done() {
                    try {
                        String err = get();
                        if (err == null) {
                            // Success path
                            Part1_DatabaseAndModel.cart.clear();
                            QuickMartApp.updateCartNav();
                            QuickMartApp.rebuildCart();
                            Part1_DatabaseAndModel.loadProductsFromDB();
                            QuickMartApp.refreshShopPanel();
                            showSuccessDialog();
                            QuickMartApp.layout.show(QuickMartApp.container, "home");
                        } else {
                            // Failure path
                            errorLbl.setText("✗  " + err);
                            payBtn.setEnabled(true);
                            payBtn.setText("Pay  ₹" + total + "  →");
                        }
                    } catch (Exception ex) {
                        errorLbl.setText("✗  Unexpected error. Please try again.");
                        payBtn.setEnabled(true);
                        payBtn.setText("Pay  ₹" + total + "  →");
                    }
                }
            }.execute();
        });
        backBtn.addActionListener(e -> QuickMartApp.showCart());

        // ── Assemble card ─────────────────────────────────────────────
        card.add(title);      card.add(Box.createVerticalStrut(18));
        card.add(summary);    card.add(Box.createVerticalStrut(22));
        card.add(mLbl);       card.add(Box.createVerticalStrut(10));
        card.add(methodGrid); card.add(Box.createVerticalStrut(18));
        card.add(Part2_UIComponents.lbl("Details (optional)", 11, Font.BOLD, TEXT_DIM));
        card.add(Box.createVerticalStrut(6));
        card.add(details);    card.add(Box.createVerticalStrut(12));
        card.add(errorLbl);   card.add(Box.createVerticalStrut(8));
        card.add(payBtn);     card.add(Box.createVerticalStrut(10));
        card.add(backBtn);

        center.add(card);
        p.add(center, BorderLayout.CENTER);
        return p;
    }

    // ════════════════════════════════════════════════════════════════════
    //  SUCCESS DIALOG
    // ════════════════════════════════════════════════════════════════════

    /**
     * Shows a modal "Payment Successful" dialog after a completed order.
     * Dismisses itself when the user clicks "Continue Shopping".
     */
    static void showSuccessDialog() {
        JDialog d = new JDialog(QuickMartApp.frame, true);
        d.setUndecorated(true);
        d.setSize(390, 260);
        d.setLocationRelativeTo(QuickMartApp.frame);

        JPanel panel = Part2_UIComponents.roundPanel(SURFACE, 20);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(34, 40, 34, 40));

        JLabel tick = Part2_UIComponents.lbl("✅", 40, Font.PLAIN, SUCCESS);
        tick.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel msg = Part2_UIComponents.lbl("Payment Successful!", 19, Font.BOLD, TEXT_PRIMARY);
        msg.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub = Part2_UIComponents.lbl(
            "Stock updated · Thank you! 🎉", 12, Font.PLAIN, TEXT_MUTED);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton ok = Part2_UIComponents.accentBtn("  Continue Shopping  ");
        ok.setAlignmentX(Component.CENTER_ALIGNMENT);
        ok.addActionListener(e -> d.dispose());

        panel.add(tick); panel.add(Box.createVerticalStrut(12));
        panel.add(msg);  panel.add(Box.createVerticalStrut(7));
        panel.add(sub);  panel.add(Box.createVerticalStrut(22));
        panel.add(ok);

        d.setContentPane(panel);
        d.setVisible(true);
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  APPLICATION ENTRY POINT
// ─────────────────────────────────────────────────────────────────────────────

/**
 * QuickMart — main application class.
 *
 * Responsibilities:
 *  1. Bootstrap: create JFrame, CardLayout, add all screen panels.
 *  2. Expose the shared helpers used by Parts 1-4:
 *       cartItemCount(), cartTotal(), updateCartNav(),
 *       rebuildCart(), showCart(),
 *       refreshHomePanel(), refreshShopPanel(), removeCard()
 *  3. Provide the {@code cartNavBtn} reference so navBar can update it.
 *  4. Run {@code main()} which seeds the offline user and starts the UI.
 */
public class QuickMartApp {

    // ── Shared UI references (read/written by all parts) ─────────────────
    static JFrame     frame;
    static CardLayout layout;
    static JPanel     container;
    static JButton    cartNavBtn = null;   // set by Part2_UIComponents.navBar()

    // ════════════════════════════════════════════════════════════════════
    //  MAIN
    // ════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {

        // Seed the always-available offline demo account
        Part1_DatabaseAndModel.offlineUsers.add(
            new String[]{"demo@quickmart.in", "demo123", "Demo User"});

        // Pre-load products (DB or fallback)
        Part1_DatabaseAndModel.loadProductsFromDB();

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(
                    UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {}

            // ── JFrame setup ──────────────────────────────────────────
            frame = new JFrame("QuickMart");
            frame.setSize(980, 680);
            frame.setMinimumSize(new Dimension(800, 560));
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLocationRelativeTo(null);
            frame.getContentPane().setBackground(Part2_UIComponents.BG);

            // ── CardLayout container ──────────────────────────────────
            layout    = new CardLayout();
            container = new JPanel(layout);
            container.setBackground(Part2_UIComponents.BG);

            // Add all screens produced by Parts 3 & 4
            JPanel loginP    = Part3_Screens.loginPanel();
            JPanel registerP = Part3_Screens.registerPanel();
            JPanel homeP     = Part3_Screens.homePanel();  homeP.setName("home");
            JPanel shopP     = Part3_Screens.shopPanel();  shopP.setName("shop");

            container.add(loginP,    "login");
            container.add(registerP, "register");
            container.add(homeP,     "home");
            container.add(shopP,     "shop");
            rebuildCart();   // adds the initial empty "cart" card

            frame.add(container);
            frame.setVisible(true);
            layout.show(container, "login");   // start on login screen
        });
    }

    // ════════════════════════════════════════════════════════════════════
    //  CART HELPERS
    // ════════════════════════════════════════════════════════════════════

    /** Total number of individual units in the cart. */
    static int cartItemCount() {
        int n = 0;
        for (Part1_DatabaseAndModel.CartItem c : Part1_DatabaseAndModel.cart) n += c.qty;
        return n;
    }

    /** Total price of all items in the cart. */
    static int cartTotal() {
        return Part1_DatabaseAndModel.cartTotal();
    }

    /** Refreshes the cart button text in the nav bar. */
    static void updateCartNav() {
        if (cartNavBtn != null)
            cartNavBtn.setText("🛒  Cart  " + cartItemCount());
    }

    /**
     * Removes and rebuilds the cart card so it reflects the latest cart state.
     * Must be called after every quantity change.
     */
    static void rebuildCart() {
        removeCard("cart");
        JPanel cp = Part3_Screens.cartPanel();
        cp.setName("cart");
        container.add(cp, "cart");
        container.revalidate();
    }

    /** Switches the visible card to the cart screen after rebuilding it. */
    static void showCart() {
        updateCartNav();
        rebuildCart();
        layout.show(container, "cart");
    }

    // ════════════════════════════════════════════════════════════════════
    //  PANEL REFRESH HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Rebuilds the home panel in-place (needed after login so the greeting
     * shows the correct user name and the DB status pill is up to date).
     */
    static void refreshHomePanel() {
        removeCard("home");
        JPanel hp = Part3_Screens.homePanel();
        hp.setName("home");
        container.add(hp, "home");
        container.revalidate();
    }

    /**
     * Rebuilds the shop panel in-place (called after a purchase or stock sync
     * so product cards reflect updated stock counts).
     */
    static void refreshShopPanel() {
        removeCard("shop");
        JPanel sp = Part3_Screens.shopPanel();
        sp.setName("shop");
        container.add(sp, "shop");
        container.revalidate();
    }

    /**
     * Removes the card with the given name from the CardLayout container.
     * No-op if the card is not found.
     *
     * @param name the component name set via {@code JPanel.setName()}
     */
    static void removeCard(String name) {
        for (Component c : container.getComponents()) {
            if (name.equals(c.getName())) {
                container.remove(c);
                return;
            }
        }
    }
}
