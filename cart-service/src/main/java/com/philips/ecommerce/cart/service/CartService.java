package com.philips.ecommerce.cart.service;

import com.philips.ecommerce.cart.client.InventoryServiceAdapter;
import com.philips.ecommerce.cart.client.ProductServiceAdapter;
import com.philips.ecommerce.cart.client.dto.AvailabilityDto;
import com.philips.ecommerce.cart.client.dto.ProductDto;
import com.philips.ecommerce.cart.dto.AddCartItemRequest;
import com.philips.ecommerce.cart.dto.CartResponse;
import com.philips.ecommerce.cart.dto.UpdateCartItemRequest;
import com.philips.ecommerce.cart.entity.Cart;
import com.philips.ecommerce.cart.entity.CartItem;
import com.philips.ecommerce.cart.mapper.CartMapper;
import com.philips.ecommerce.cart.repository.CartRepository;
import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Cart use cases.
 *
 * <h3>Transaction boundaries</h3>
 * Every use case that needs product/stock data follows the same three steps:
 * <ol>
 *   <li>short read-only transaction: cheap local checks (line exists? limit?) - fail fast without remote calls,</li>
 *   <li>remote calls to product-service and inventory-service with NO transaction open,</li>
 *   <li>short write transaction: reload the cart, re-validate what may have changed meanwhile, apply, save.</li>
 * </ol>
 * Holding a DB transaction during HTTP calls would pin a pooled JDBC connection (and possibly row locks) for up to
 * the remote timeout x retries; a slow dependency would then exhaust the Hikari pool (10 connections) and take this
 * service down too. Transactions are opened programmatically with {@link TransactionTemplate} so the boundary is
 * visible in the code (and the unit tests run the callbacks with a mocked transaction manager).
 *
 * <p>The stock check is advisory (stock can change after it); the authoritative check is the reservation at
 * checkout. Concurrent edits of one cart are caught by the optimistic lock on {@link Cart} (409).
 */
@Slf4j
@Service
public class CartService {

    public static final int MAX_QUANTITY_PER_LINE = 10;
    public static final String DEFAULT_CURRENCY = "EUR";

    private final CartRepository cartRepository;
    private final ProductServiceAdapter productAdapter;
    private final InventoryServiceAdapter inventoryAdapter;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public CartService(CartRepository cartRepository,
                       ProductServiceAdapter productAdapter,
                       InventoryServiceAdapter inventoryAdapter,
                       PlatformTransactionManager transactionManager) {
        this.cartRepository = cartRepository;
        this.productAdapter = productAdapter;
        this.inventoryAdapter = inventoryAdapter;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public CartResponse getCart(String customerId) {
        return readTransaction.execute(status -> cartRepository.findWithItemsByCustomerId(customerId)
                .map(CartMapper::toResponse)
                .orElseGet(() -> CartMapper.empty(customerId, DEFAULT_CURRENCY)));
    }

    /** Adds {@code quantity} to the product's line (creating cart and/or line when needed). */
    public CartResponse addItem(final String customerId, AddCartItemRequest request) {
        final Long productId = request.getProductId();
        final int added = request.getQuantity();

        // 1) local pre-check: the merged line must stay within the limit
        Integer current = readTransaction.execute(status -> currentQuantity(customerId, productId));
        int target = requireWithinLimit(productId, (current == null ? 0 : current) + added);

        // 2) remote calls, outside any transaction
        final ProductDto product = requireActive(productAdapter.getProduct(productId));
        requireInStock(productId, target);

        // 3) apply
        return writeTransaction.execute(status -> {
            Cart cart = cartRepository.findWithItemsByCustomerId(customerId)
                    .orElseGet(() -> new Cart(customerId, currencyOf(product)));
            requireSameCurrency(cart, product);
            Optional<CartItem> line = cart.findItem(productId);
            if (line.isPresent()) {
                // re-check: another request may have increased the line since step 1
                line.get().changeQuantity(requireWithinLimit(productId, line.get().getQuantity() + added));
                line.get().refreshSnapshot(product.getSku(), product.getName(), product.getPrice());
            } else {
                cart.addItem(productId, product.getSku(), product.getName(), product.getPrice(), added);
            }
            cart.touch();
            log.info("Customer {} added {} x product {} to the cart", customerId, added, productId);
            return CartMapper.toResponse(cartRepository.saveAndFlush(cart));
        });
    }

    /** Sets the absolute quantity of an existing line and refreshes its price snapshot. */
    public CartResponse updateItem(final String customerId, final Long productId, UpdateCartItemRequest request) {
        final int quantity = requireWithinLimit(productId, request.getQuantity());

        // 1) 404 before any remote call
        readTransaction.execute(status -> requireLine(loadCart(customerId, productId), customerId, productId));

        // 2) remote calls, outside any transaction
        final ProductDto product = requireActive(productAdapter.getProduct(productId));
        requireInStock(productId, quantity);

        // 3) apply (the line may have been removed meanwhile -> 404 again)
        return writeTransaction.execute(status -> {
            Cart cart = loadCart(customerId, productId);
            CartItem line = requireLine(cart, customerId, productId);
            requireSameCurrencyForUpdate(cart, product);
            line.changeQuantity(quantity);
            line.refreshSnapshot(product.getSku(), product.getName(), product.getPrice());
            cart.touch();
            log.info("Customer {} set quantity of product {} to {}", customerId, productId, quantity);
            return CartMapper.toResponse(cartRepository.saveAndFlush(cart));
        });
    }

    public CartResponse removeItem(final String customerId, final Long productId) {
        return writeTransaction.execute(status -> {
            Cart cart = loadCart(customerId, productId);
            requireLine(cart, customerId, productId);
            cart.removeItem(productId);
            cart.touch();
            log.info("Customer {} removed product {} from the cart", customerId, productId);
            return CartMapper.toResponse(cartRepository.saveAndFlush(cart));
        });
    }

    /**
     * Deletes the cart (DELETE endpoint and order-confirmed event). Idempotent: no cart -> nothing to do.
     * Joins the caller's transaction when there is one (Kafka handler: same transaction as processed_event).
     *
     * @return true when a cart existed
     */
    public boolean clearCart(final String customerId) {
        Boolean deleted = writeTransaction.execute(status -> {
            Optional<Cart> cart = cartRepository.findWithItemsByCustomerId(customerId);
            if (!cart.isPresent()) {
                return Boolean.FALSE;
            }
            cartRepository.delete(cart.get());
            log.info("Cleared cart of customer {}", customerId);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(deleted);
    }

    // ------------------------------------------------------------------------------------------------------------

    private int currentQuantity(String customerId, Long productId) {
        Optional<Cart> cart = cartRepository.findWithItemsByCustomerId(customerId);
        if (!cart.isPresent()) {
            return 0;
        }
        Optional<CartItem> line = cart.get().findItem(productId);
        return line.isPresent() ? line.get().getQuantity() : 0;
    }

    /** No cart at all also means "this product is not in the cart" -> CART_ITEM_NOT_FOUND. */
    private Cart loadCart(String customerId, Long productId) {
        return cartRepository.findWithItemsByCustomerId(customerId)
                .orElseThrow(() -> cartItemNotFound(customerId, productId));
    }

    private static CartItem requireLine(Cart cart, String customerId, Long productId) {
        Optional<CartItem> line = cart.findItem(productId);
        if (!line.isPresent()) {
            throw cartItemNotFound(customerId, productId);
        }
        return line.get();
    }

    private static int requireWithinLimit(Long productId, int quantity) {
        if (quantity > MAX_QUANTITY_PER_LINE) {
            throw new BadRequestException("QUANTITY_LIMIT_EXCEEDED", "At most " + MAX_QUANTITY_PER_LINE
                    + " units of a product per cart; requested " + quantity)
                    .withDetail("productId", productId)
                    .withDetail("requested", quantity)
                    .withDetail("max", MAX_QUANTITY_PER_LINE);
        }
        return quantity;
    }

    private static ProductDto requireActive(ProductDto product) {
        if (!product.isActive()) {
            throw new BadRequestException("PRODUCT_INACTIVE", "Product " + product.getId()
                    + " is no longer sold").withDetail("productId", product.getId());
        }
        if (product.getPrice() == null) {
            throw new IllegalStateException("product-service returned product " + product.getId() + " without price");
        }
        return product;
    }

    private void requireInStock(Long productId, int quantity) {
        AvailabilityDto availability = inventoryAdapter.checkAvailability(productId, quantity);
        if (!availability.isAvailable()) {
            throw new ConflictException("INSUFFICIENT_STOCK", "Only " + availability.getAvailableQuantity()
                    + " units of product " + productId + " available, requested " + quantity)
                    .withDetail("productId", productId)
                    .withDetail("requested", quantity)
                    .withDetail("available", availability.getAvailableQuantity());
        }
    }

    /** All lines of a cart share one currency, otherwise the subtotal would be meaningless. */
    private static void requireSameCurrency(Cart cart, ProductDto product) {
        String productCurrency = currencyOf(product);
        if (cart.isEmpty()) {
            if (!productCurrency.equals(cart.getCurrency())) {
                cart.adoptCurrency(productCurrency);
            }
            return;
        }
        if (!productCurrency.equals(cart.getCurrency())) {
            throw currencyMismatch(cart, product, productCurrency);
        }
    }

    /** On update the cart is never empty (the line exists), so the currency must simply match. */
    private static void requireSameCurrencyForUpdate(Cart cart, ProductDto product) {
        String productCurrency = currencyOf(product);
        if (!productCurrency.equals(cart.getCurrency())) {
            throw currencyMismatch(cart, product, productCurrency);
        }
    }

    private static BusinessException currencyMismatch(Cart cart, ProductDto product, String productCurrency) {
        return new BadRequestException("CURRENCY_MISMATCH", "Product " + product.getId() + " is priced in "
                + productCurrency + " but the cart uses " + cart.getCurrency())
                .withDetail("productId", product.getId())
                .withDetail("productCurrency", productCurrency)
                .withDetail("cartCurrency", cart.getCurrency());
    }

    private static String currencyOf(ProductDto product) {
        return StringUtils.hasText(product.getCurrency()) ? product.getCurrency() : DEFAULT_CURRENCY;
    }

    private static BusinessException cartItemNotFound(String customerId, Long productId) {
        return new ResourceNotFoundException("CART_ITEM_NOT_FOUND",
                "Product " + productId + " is not in the cart of customer " + customerId)
                .withDetail("customerId", customerId)
                .withDetail("productId", productId);
    }
}
