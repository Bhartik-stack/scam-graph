package com.scamgraph.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Database access for transactions. Spring Data JPA writes the SQL for us:
 * save, saveAll, findAll, count, deleteAll ... are all available automatically.
 */
public interface TransactionRepository extends JpaRepository<TransactionRecord, Long> {
}
