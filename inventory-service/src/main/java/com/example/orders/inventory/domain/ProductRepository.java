package com.example.orders.inventory.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Catalog access: by id or SKU for writes, paged and searchable for the public catalog. */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    /**
     * @param term     substring matched case-insensitively against name and SKU
     * @param pageable page request
     * @return the matching page
     */
    @Query("""
            select p from Product p
            where lower(p.name) like lower(concat('%', :term, '%'))
               or lower(p.sku) like lower(concat('%', :term, '%'))
            """)
    Page<Product> search(@Param("term") String term, Pageable pageable);

    /**
     * Loads the products of one reservation request and locks their rows until the transaction ends.
     *
     * <p>Ordering by id is what keeps concurrent multi-line reservations deadlock-free, and taking
     * the row lock up front means 20 concurrent callers queue up instead of all reading the same
     * stock, racing on {@code @Version} and burning their retries — the reason this is a write lock
     * and not a plain read.
     *
     * @param ids products to lock
     * @return the found products; missing ids are simply absent
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> lockAllByIdIn(@Param("ids") Collection<UUID> ids);
}
