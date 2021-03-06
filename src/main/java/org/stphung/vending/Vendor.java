package org.stphung.vending;

import com.google.common.collect.ImmutableList;
import org.stphung.openkore.Openkore;
import org.stphung.openkore.OpenkoreException;
import org.stphung.pricing.ItemDataProvider;
import org.stphung.pricing.ragial.RagialItemDataProvider;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

public class Vendor implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(Vendor.class.getCanonicalName());
    private final String shopName;
    private final Openkore openkore;
    private final List<VendorListener> vendorListeners;
    private final Map<String, Offer> offers;
    private String id;

    public Vendor(String openkoreHome, String shopName, String id) {
        this.id = id;
        this.shopName = shopName;
        this.openkore = new Openkore(openkoreHome);
        this.vendorListeners = new ArrayList<>();
        this.offers = new HashMap<>();
    }

    private static String readFile(String path, Charset encoding) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }

    public String getId() {
        return this.id;
    }

    public void addVendorListener(VendorListener listener) {
        this.vendorListeners.add(listener);
    }

    public Openkore getOpenkore() {
        return this.openkore;
    }

    public void init() throws IOException, OpenkoreException, InterruptedException, URISyntaxException {
        List<CartItem> latestCartItems = this.getCartItems();
        this.createOffer(Planner.getInstance(), latestCartItems, RagialItemDataProvider.getInstance());
    }

    public List<CartItem> getCartItems() throws OpenkoreException, FileNotFoundException, InterruptedException {
        this.openkore.start();
        Thread.sleep(25000);
        this.openkore.close();

        Scanner sc = new Scanner(new File(openkore.getConsoleLogPath()));
        List<CartItem> latestCartItems = null;
        while (sc.hasNextLine()) {
            String line = sc.nextLine();
            if (line.contains("---------------------- Cart ----------------------")) {
                latestCartItems = getCartItems(sc);
            }
        }

        return latestCartItems;
    }

    private List<CartItem> getCartItems(Scanner sc) throws FileNotFoundException {
        ImmutableList.Builder<CartItem> cartItemsBuilder = ImmutableList.builder();

        sc.nextLine();

        // parse cart items
        String line = sc.nextLine();
        while (!line.isEmpty()) {
            String[] tokens = line.split(" ");
            int count = Integer.parseInt(tokens[tokens.length - 1]);
            StringBuilder nameSb = new StringBuilder();
            for (int i = 1; i < tokens.length - 2; i++) {
                nameSb.append(tokens[i]).append(' ');
            }

            String name = nameSb.toString().trim();
            CartItem cartItem = new CartItem(name, count);
            cartItemsBuilder.add(cartItem);

            line = sc.nextLine();
        }

        ImmutableList<CartItem> cartItems = cartItemsBuilder.build();
        return cartItems;
    }

    public void createOffer(Planner planner, List<CartItem> cartItems, ItemDataProvider itemDataProvider) throws IOException, URISyntaxException {
        Offer offer = planner.getOffer(cartItems, itemDataProvider);
        putOffer(offer);
    }

    private void putOffer(Offer offer) {
        this.offers.put(offer.getId(), offer);
        for (VendorListener vendorListener : this.vendorListeners) {
            vendorListener.offerCreated(this.id, offer);
        }
    }

    public void confirmOffer(String id) throws FileNotFoundException {
        Offer offer = this.offers.get(id);
        if (offer != null) {
            this.setOffer(offer);
        }
    }

    public void modifyPrice(Offer offer, int itemIndex, int price) {
        Offer newOffer = offer.modifyPrice(itemIndex, price);
        this.putOffer(newOffer);
    }

    public void modifyCount(Offer offer, int itemIndex, int count) {
        Offer newOffer = offer.modifyCount(itemIndex, count);
        this.putOffer(newOffer);
    }


    private void setOffer(Offer offer) throws FileNotFoundException {
        String shopConfigPath = this.openkore.getShopConfigPath();
        LOGGER.info("writing openkore shop config to " + shopConfigPath);
        try (PrintWriter pw = new PrintWriter(shopConfigPath)) {
            pw.println(this.shopName);
            pw.println();
            for (ShopEntry item : offer.getShopEntries()) {
                pw.println(item.getName() + '\t' + item.getFormattedPrice() + '\t' + item.getCount());
            }
        }
    }

    public Optional<Offer> getOffer(String id) {
        if (this.offers.containsKey(id)) {
            return Optional.of(this.offers.get(id));
        }
        return Optional.empty();
    }

    public String getShopConfig() throws IOException {
        return readFile(this.openkore.getShopConfigPath(), Charset.defaultCharset());
    }

    public void start() throws OpenkoreException {
        LOGGER.info("start called");
        this.openkore.start();
    }

    @Override
    public void close() throws IOException {
        this.openkore.close();
    }
}
