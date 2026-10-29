package com.philips.ecommerce.cart.repository;

import com.philips.ecommerce.cart.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

	/**
	 * Cart + all items in ONE SQL statement (left join fetch). Loading the cart and
	 * then touching the lazy items collection would issue a second query; doing
	 * that for a list of carts is the classic N+1 problem. "distinct" removes the
	 * duplicated root rows a collection fetch join produces.
	 */
	@Query("select distinct c from Cart c left join fetch c.items where c.customerId = :customerId")
	Optional<Cart> findWithItemsByCustomerId(@Param("customerId") String customerId);
}
