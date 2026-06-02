ALTER TABLE published_preset ADD COLUMN slug VARCHAR(160);

UPDATE published_preset
SET slug = 'preset-' || id
WHERE slug IS NULL;

ALTER TABLE published_preset ALTER COLUMN slug SET NOT NULL;

CREATE UNIQUE INDEX uk_published_preset_slug ON published_preset(slug);
