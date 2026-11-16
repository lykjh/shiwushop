ALTER TABLE products ADD FULLTEXT INDEX ft_products_discovery_name (name) WITH PARSER ngram;
ALTER TABLE shops ADD FULLTEXT INDEX ft_shops_discovery_name (name) WITH PARSER ngram;
ALTER TABLE brands ADD FULLTEXT INDEX ft_brands_discovery_name (name) WITH PARSER ngram;
