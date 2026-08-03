-- Personal dashboard: per-user "last visited the dashboard" marker. Drives the "new pings since
-- your last visit" count on the dashboard (discussion comments created after this timestamp).
-- Null = never visited (first visit shows recent pings, then stamps now()).
ALTER TABLE public.app_user ADD COLUMN dashboard_seen_at timestamptz;
