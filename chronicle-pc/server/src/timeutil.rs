//! Timestamp parsing/formatting parity with Python's datetime + zoneinfo.

use chrono::{DateTime, FixedOffset, Local, NaiveDate, NaiveDateTime, TimeZone, Utc};
use chrono_tz::Tz;

/// Parse an ISO-8601 timestamp the way `datetime.fromisoformat` does for the
/// inputs Chronicle actually sees (offsets, "Z", optional fractional seconds).
pub fn parse_iso(s: &str) -> Option<DateTime<FixedOffset>> {
    let s = s.trim();
    if s.is_empty() {
        return None;
    }
    // fromisoformat accepts 'Z' since 3.11.
    let normalized = if s.ends_with('Z') || s.ends_with('z') {
        format!("{}+00:00", &s[..s.len() - 1])
    } else {
        s.to_string()
    };
    if let Ok(dt) = DateTime::parse_from_rfc3339(&normalized) {
        return Some(dt);
    }
    // Naive variants (no offset) → naive flag tells caller to attach a zone.
    if NaiveDateTime::parse_from_str(&normalized, "%Y-%m-%dT%H:%M:%S%.f").is_ok() {
        return None;
    }
    if NaiveDate::parse_from_str(&normalized, "%Y-%m-%d").is_ok() {
        return None;
    }
    None
}

/// Parse, returning the parsed datetime plus whether it was naive (no offset).
pub fn parse_iso_aware(s: &str) -> Option<(DateTime<FixedOffset>, bool)> {
    let s = s.trim();
    if s.is_empty() {
        return None;
    }
    let normalized = if s.ends_with('Z') || s.ends_with('z') {
        format!("{}+00:00", &s[..s.len() - 1])
    } else {
        s.to_string()
    };
    if let Ok(dt) = DateTime::parse_from_rfc3339(&normalized) {
        return Some((dt, false));
    }
    for fmt in ["%Y-%m-%dT%H:%M:%S%.f", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S%.f", "%Y-%m-%d %H:%M:%S"] {
        if let Ok(ndt) = NaiveDateTime::parse_from_str(&normalized, fmt) {
            return Some((fixed_from_naive(ndt), true));
        }
    }
    None
}

pub fn fixed_from_naive(ndt: NaiveDateTime) -> DateTime<FixedOffset> {
    let local_offset = *Local::now().offset();
    local_offset
        .from_local_datetime(&ndt)
        .single()
        .unwrap_or_else(|| {
            FixedOffset::east_opt(0)
                .unwrap()
                .from_utc_datetime(&ndt)
        })
}

fn tz_by_name(name: &str) -> Option<Tz> {
    name.parse::<Tz>().ok()
}

pub fn now_in_tz(tz_name: &str) -> NaiveDate {
    match tz_by_name(tz_name) {
        Some(tz) => Utc::now().with_timezone(&tz).date_naive(),
        None => Local::now().date_naive(),
    }
}

pub fn today_utc() -> NaiveDate {
    Utc::now().date_naive()
}

/// now_iso(): UTC seconds-precision ISO with +00:00 offset (brain/util.py).
pub fn now_iso() -> String {
    Utc::now().format("%Y-%m-%dT%H:%M:%S+00:00").to_string()
}

/// entry_day chain: ISO parse (naive → fallback_tz attach) → id date → today
/// in fallback_tz. `id` is the entry id whose first 10 chars are a date.
pub fn entry_day(ts: &str, id: &str, fallback_tz: &str) -> NaiveDate {
    if let Some((dt, naive)) = parse_iso_aware(ts) {
        if !naive {
            return dt.date_naive();
        }
        if let Some(tz) = tz_by_name(fallback_tz) {
            let utc = dt.with_timezone(&Utc);
            return utc.with_timezone(&tz).date_naive();
        }
        return dt.date_naive();
    }
    // `get(..10)` is char-boundary-safe: a multibyte id (hand-edited/synced
    // file) yields None instead of panicking the request thread.
    if let Some(head) = id.get(..10) {
        if let Ok(d) = NaiveDate::parse_from_str(head, "%Y-%m-%d") {
            return d;
        }
    }
    now_in_tz(fallback_tz)
}

/// Format a python-style isoformat(timespec="seconds") local-aware stamp.
pub fn iso_seconds(dt: DateTime<FixedOffset>) -> String {
    dt.format("%Y-%m-%dT%H:%M:%S%:z").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_offsets_and_z() {
        assert!(parse_iso_aware("2026-08-21T10:15:00+05:30").is_some());
        assert!(parse_iso_aware("2026-08-21T10:15:00Z").is_some());
        let (dt, naive) = parse_iso_aware("2026-08-21T10:15:00").unwrap();
        assert!(naive);
        assert_eq!(dt.format("%Y-%m-%dT%H:%M:%S").to_string(), "2026-08-21T10:15:00");
    }

    #[test]
    fn entry_day_fallback_chain() {
        assert_eq!(entry_day("2026-08-21T10:15:00+05:30", "x", "UTC"), NaiveDate::from_ymd_opt(2026, 8, 21).unwrap());
        assert_eq!(entry_day("garbage", "2026-08-20_101500-an", "UTC"), NaiveDate::from_ymd_opt(2026, 8, 20).unwrap());
        assert_eq!(entry_day("garbage", "bad-id", "UTC"), today_utc());
    }

    #[test]
    fn entry_day_multibyte_id_does_not_panic() {
        // 9 ASCII bytes + a multibyte char: byte offset 10 lands mid-character.
        // Regression: `&id[..10]` panicked here on hand-edited/synced ids.
        assert_eq!(entry_day("garbage", "aaaaaaaaa日本", "UTC"), today_utc());
        // ts slicing path in brain must behave identically.
        assert_eq!(entry_day("日本語のタイムスタンプ", "bad-id", "UTC"), today_utc());
    }

    #[test]
    fn now_iso_shape() {
        let s = now_iso();
        assert!(s.len() == 25 && s.ends_with("+00:00"), "{s}");
    }
}
