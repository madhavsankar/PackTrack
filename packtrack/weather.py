"""Weather and Destination Intelligence service for PackTrack.

Uses Open-Meteo (free, no API key required) when network is available,
and falls back to built-in seasonal climate intelligence when offline.
"""

import json
import urllib.request
import urllib.parse
from datetime import datetime, timedelta
from typing import Dict, Any, List, Optional
from .models import DestinationWeatherSummary, WeatherForecastDay


# Country plug & currency metadata for automatic international suggestions
COUNTRY_METADATA: Dict[str, Dict[str, str]] = {
    "US": {"country": "United States", "plug": "Type A/B (120V)", "currency": "USD ($)"},
    "GB": {"country": "United Kingdom", "plug": "Type G (3-pin UK, 230V)", "currency": "GBP (£)"},
    "JP": {"country": "Japan", "plug": "Type A/B (100V)", "currency": "JPY (¥)"},
    "FR": {"country": "France", "plug": "Type C/E (EU 2-pin, 230V)", "currency": "EUR (€)"},
    "DE": {"country": "Germany", "plug": "Type C/F (EU 2-pin, 230V)", "currency": "EUR (€)"},
    "IT": {"country": "Italy", "plug": "Type C/F/L (EU, 230V)", "currency": "EUR (€)"},
    "ES": {"country": "Spain", "plug": "Type C/F (EU, 230V)", "currency": "EUR (€)"},
    "CH": {"country": "Switzerland", "plug": "Type C/J (Swiss 3-pin, 230V)", "currency": "CHF"},
    "IN": {"country": "India", "plug": "Type C/D/M (230V)", "currency": "INR (₹)"},
    "SG": {"country": "Singapore", "plug": "Type G (230V)", "currency": "SGD (S$)"},
    "AU": {"country": "Australia", "plug": "Type I (230V)", "currency": "AUD (A$)"},
    "CA": {"country": "Canada", "plug": "Type A/B (120V)", "currency": "CAD (C$)"},
    "MX": {"country": "Mexico", "plug": "Type A/B (127V)", "currency": "MXN ($)"},
}

# Built-in fallback city database for fast offline operation or distant dates
KNOWN_CITIES: Dict[str, Dict[str, Any]] = {
    "london": {"name": "London", "country": "United Kingdom", "code": "GB", "lat": 51.5074, "lon": -0.1278, "base_high_f": 56, "base_low_f": 43, "rain_prob": 55},
    "tokyo": {"name": "Tokyo", "country": "Japan", "code": "JP", "lat": 35.6762, "lon": 139.6503, "base_high_f": 64, "base_low_f": 49, "rain_prob": 30},
    "new york": {"name": "New York", "country": "United States", "code": "US", "lat": 40.7128, "lon": -74.0060, "base_high_f": 58, "base_low_f": 44, "rain_prob": 30},
    "nyc": {"name": "New York", "country": "United States", "code": "US", "lat": 40.7128, "lon": -74.0060, "base_high_f": 58, "base_low_f": 44, "rain_prob": 30},
    "san francisco": {"name": "San Francisco", "country": "United States", "code": "US", "lat": 37.7749, "lon": -122.4194, "base_high_f": 63, "base_low_f": 51, "rain_prob": 20},
    "seattle": {"name": "Seattle", "country": "United States", "code": "US", "lat": 47.6062, "lon": -122.3321, "base_high_f": 54, "base_low_f": 42, "rain_prob": 65},
    "austin": {"name": "Austin", "country": "United States", "code": "US", "lat": 30.2672, "lon": -97.7431, "base_high_f": 78, "base_low_f": 58, "rain_prob": 20},
    "chicago": {"name": "Chicago", "country": "United States", "code": "US", "lat": 41.8781, "lon": -87.6298, "base_high_f": 48, "base_low_f": 34, "rain_prob": 35},
    "miami": {"name": "Miami", "country": "United States", "code": "US", "lat": 25.7617, "lon": -80.1918, "base_high_f": 83, "base_low_f": 72, "rain_prob": 40},
    "las vegas": {"name": "Las Vegas", "country": "United States", "code": "US", "lat": 36.1699, "lon": -115.1398, "base_high_f": 76, "base_low_f": 54, "rain_prob": 10},
    "paris": {"name": "Paris", "country": "France", "code": "FR", "lat": 48.8566, "lon": 2.3522, "base_high_f": 57, "base_low_f": 44, "rain_prob": 45},
    "zurich": {"name": "Zurich", "country": "Switzerland", "code": "CH", "lat": 47.3769, "lon": 8.5417, "base_high_f": 48, "base_low_f": 35, "rain_prob": 45},
    "bengaluru": {"name": "Bengaluru", "country": "India", "code": "IN", "lat": 12.9716, "lon": 77.5946, "base_high_f": 82, "base_low_f": 65, "rain_prob": 35},
    "mumbai": {"name": "Mumbai", "country": "India", "code": "IN", "lat": 19.0760, "lon": 72.8777, "base_high_f": 88, "base_low_f": 75, "rain_prob": 30},
    "singapore": {"name": "Singapore", "country": "Singapore", "code": "SG", "lat": 1.3521, "lon": 103.8198, "base_high_f": 88, "base_low_f": 78, "rain_prob": 50},
    "sydney": {"name": "Sydney", "country": "Australia", "code": "AU", "lat": -33.8688, "lon": 151.2093, "base_high_f": 74, "base_low_f": 61, "rain_prob": 30},
}


def f_to_c(temp_f: float) -> float:
    return round((temp_f - 32.0) * 5.0 / 9.0, 1)


def c_to_f(temp_c: float) -> float:
    return round((temp_c * 9.0 / 5.0) + 32.0, 1)


def _wmo_code_to_condition(code: int, temp_min_f: float) -> str:
    if code in (71, 73, 75, 77, 85, 86) or temp_min_f <= 30:
        return "Snow"
    if code in (51, 53, 55, 61, 63, 65, 80, 81, 82, 95, 96, 99):
        return "Rain"
    if code in (1, 2, 3, 45, 48):
        return "Cloudy"
    return "Sunny"


def fetch_destination_weather(
    destination: str,
    start_date_str: Optional[str] = None,
    duration_days: int = 5,
    home_country_code: str = "US",
) -> DestinationWeatherSummary:
    """Fetches real forecast from Open-Meteo or falls back to seasonal climate profile."""
    duration_days = max(1, min(duration_days, 30))
    if not start_date_str:
        start_dt = datetime.now() + timedelta(days=2)
        start_date_str = start_dt.strftime("%Y-%m-%d")
    else:
        try:
            start_dt = datetime.strptime(start_date_str, "%Y-%m-%d")
        except ValueError:
            start_dt = datetime.now() + timedelta(days=2)
            start_date_str = start_dt.strftime("%Y-%m-%d")

    dest_key = destination.strip().lower()
    city_info = None
    for k, v in KNOWN_CITIES.items():
        if k in dest_key or dest_key in k:
            city_info = v
            break

    lat, lon = (city_info["lat"], city_info["lon"]) if city_info else (37.7749, -122.4194)
    dest_name = city_info["name"] if city_info else destination.strip().title()
    country_name = city_info["country"] if city_info else "United States"
    country_code = city_info["code"] if city_info else "US"

    # Try live Open-Meteo Geocoding + Forecast (1.5s timeout so it never hangs offline)
    daily_forecasts: List[WeatherForecastDay] = []
    forecast_source = "climate_estimate"

    try:
        if not city_info:
            geo_url = (
                "https://geocoding-api.open-meteo.com/v1/search?"
                + urllib.parse.urlencode({"name": destination, "count": 1, "language": "en", "format": "json"})
            )
            req = urllib.request.Request(geo_url, headers={"User-Agent": "PackTrack/1.0"})
            with urllib.request.urlopen(req, timeout=2.0) as resp:
                geo_data = json.loads(resp.read().decode("utf-8"))
                if geo_data.get("results"):
                    top = geo_data["results"][0]
                    lat = top["latitude"]
                    lon = top["longitude"]
                    dest_name = top.get("name", dest_name)
                    country_name = top.get("country", country_name)
                    country_code = top.get("country_code", country_code).upper()

        weather_url = (
            "https://api.open-meteo.com/v1/forecast?"
            + urllib.parse.urlencode({
                "latitude": lat,
                "longitude": lon,
                "daily": "weathercode,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                "temperature_unit": "fahrenheit",
                "timezone": "auto",
                "forecast_days": min(14, duration_days + 2),
            })
        )
        req = urllib.request.Request(weather_url, headers={"User-Agent": "PackTrack/1.0"})
        with urllib.request.urlopen(req, timeout=2.5) as resp:
            w_data = json.loads(resp.read().decode("utf-8"))
            daily = w_data.get("daily", {})
            dates = daily.get("time", [])
            max_temps = daily.get("temperature_2m_max", [])
            min_temps = daily.get("temperature_2m_min", [])
            precip_probs = daily.get("precipitation_probability_max", [])
            codes = daily.get("weathercode", [])

            for i in range(min(duration_days, len(dates))):
                t_max_f = round(float(max_temps[i]), 1)
                t_min_f = round(float(min_temps[i]), 1)
                p_prob = int(precip_probs[i] or 0) if i < len(precip_probs) else 20
                w_code = int(codes[i] or 0) if i < len(codes) else 0
                cond = _wmo_code_to_condition(w_code, t_min_f)
                if p_prob >= 45 and cond == "Sunny":
                    cond = "Rain"
                daily_forecasts.append(
                    WeatherForecastDay(
                        date=dates[i],
                        temp_max_f=t_max_f,
                        temp_min_f=t_min_f,
                        temp_max_c=f_to_c(t_max_f),
                        temp_min_c=f_to_c(t_min_f),
                        precip_prob=p_prob,
                        condition=cond,
                    )
                )
            if daily_forecasts:
                forecast_source = "live_open_meteo"
    except Exception:
        # Offline or distant future dates: synthesize seasonal climate estimate
        pass

    if not daily_forecasts:
        month = start_dt.month
        # Seasonal adjustment for Northern/Southern hemisphere
        seasonal_offset_f = 0.0
        if month in (12, 1, 2):
            seasonal_offset_f = -12.0 if lat >= 0 else 10.0
        elif month in (6, 7, 8):
            seasonal_offset_f = 14.0 if lat >= 0 else -10.0

        base_high = (city_info["base_high_f"] if city_info else 68.0) + seasonal_offset_f
        base_low = (city_info["base_low_f"] if city_info else 52.0) + seasonal_offset_f
        base_rain = city_info["rain_prob"] if city_info else 25

        for d in range(duration_days):
            day_dt = start_dt + timedelta(days=d)
            variation = ((d % 3) - 1) * 2.5
            t_max_f = round(base_high + variation, 1)
            t_min_f = round(base_low + variation, 1)
            p_prob = base_rain + (15 if d % 2 == 1 else -10)
            p_prob = max(5, min(90, p_prob))
            cond = "Snow" if (t_min_f <= 30 and p_prob >= 35) else ("Rain" if p_prob >= 40 else ("Cloudy" if p_prob >= 25 else "Sunny"))
            daily_forecasts.append(
                WeatherForecastDay(
                    date=day_dt.strftime("%Y-%m-%d"),
                    temp_max_f=t_max_f,
                    temp_min_f=t_min_f,
                    temp_max_c=f_to_c(t_max_f),
                    temp_min_c=f_to_c(t_min_f),
                    precip_prob=p_prob,
                    condition=cond,
                )
            )

    avg_high_f = round(sum(d.temp_max_f for d in daily_forecasts) / len(daily_forecasts), 1)
    avg_low_f = round(sum(d.temp_min_f for d in daily_forecasts) / len(daily_forecasts), 1)
    rainy_days = sum(1 for d in daily_forecasts if d.condition == "Rain" or d.precip_prob >= 40)
    snowy_days = sum(1 for d in daily_forecasts if d.condition == "Snow" or d.temp_min_f <= 32)
    max_precip = max((d.precip_prob for d in daily_forecasts), default=0)

    is_intl = country_code.upper() != home_country_code.upper()
    meta = COUNTRY_METADATA.get(country_code.upper(), {"plug": "Universal Adapter recommended", "currency": "Local Currency"})

    return DestinationWeatherSummary(
        destination_name=dest_name,
        country=country_name,
        country_code=country_code,
        latitude=lat,
        longitude=lon,
        start_date=start_date_str,
        duration_days=duration_days,
        avg_high_f=avg_high_f,
        avg_low_f=avg_low_f,
        avg_high_c=f_to_c(avg_high_f),
        avg_low_c=f_to_c(avg_low_f),
        rainy_days_count=rainy_days,
        snowy_days_count=snowy_days,
        max_precip_prob=max_precip,
        is_international=is_intl,
        plug_types=meta["plug"],
        currency=meta["currency"],
        daily_forecasts=daily_forecasts,
        forecast_source=forecast_source,
    )

