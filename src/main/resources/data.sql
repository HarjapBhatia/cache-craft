INSERT INTO items (id, name, price, description)
SELECT series,
       'Item ' || series,
       (series * 37) % 10000 + 100,
       'Deterministic seed item ' || series || ' for cache saturation experiments.'
FROM generate_series(1, 10000) AS series
ON CONFLICT (id) DO NOTHING;
