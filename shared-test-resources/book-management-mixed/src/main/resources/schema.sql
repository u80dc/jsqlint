CREATE TABLE IF NOT EXISTS authors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(96) NOT NULL,
    country VARCHAR(64) NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    KEY idx_authors_name (name),
    KEY idx_authors_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS books (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    isbn VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    author_id BIGINT NOT NULL,
    category VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    price DECIMAL(10, 2) NULL,
    stock INT NOT NULL DEFAULT 0,
    published_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    UNIQUE KEY uk_books_isbn (isbn),
    KEY idx_books_author (author_id),
    KEY idx_books_status (status),
    KEY idx_books_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS borrow_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id BIGINT NOT NULL,
    borrower_name VARCHAR(96) NOT NULL,
    borrower_phone VARCHAR(32) NULL,
    status VARCHAR(24) NOT NULL,
    borrowed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    due_at TIMESTAMP NULL,
    returned_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    KEY idx_borrow_records_book (book_id),
    KEY idx_borrow_records_status (status),
    KEY idx_borrow_records_borrower (borrower_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
