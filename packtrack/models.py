"""Data models and generic default templates for PackTrack."""

from dataclasses import dataclass, field, asdict
from typing import List, Dict, Optional, Any


CATEGORY_BASICS = "Basics (Leave-the-House)"
CATEGORY_DOCUMENTS = "Documents"
CATEGORY_OFFICE_WORK = "Office / Work"
CATEGORY_CLOTHES = "Clothes"
CATEGORY_TOILETRIES = "Toiletries & Health"
CATEGORY_TECH = "Tech & Chargers"
CATEGORY_PACKING_AIDS = "Packing & Travel Gear"
CATEGORY_ACTIVITIES = "Activity Gear"


@dataclass
class TemplateItemRule:
    name: str
    category: str
    scale_by_days: bool = False
    ratio_per_day: float = 1.0
    extra_buffer: int = 0
    max_quantity: int = 14
    is_doorway_critical: bool = False  # Highlighted in "Leave the House" check
    is_high_loss_return: bool = False  # Highlighted in "Hotel Checkout Sweep"
    bag_location: str = "Carry-On"     # "Personal Item", "Carry-On", "Checked", "Worn"
    notes: str = ""


@dataclass
class WeatherForecastDay:
    date: str
    temp_max_f: float
    temp_min_f: float
    temp_max_c: float
    temp_min_c: float
    precip_prob: int
    condition: str  # "Sunny", "Cloudy", "Rain", "Snow"


@dataclass
class DestinationWeatherSummary:
    destination_name: str
    country: str
    country_code: str
    latitude: float
    longitude: float
    start_date: str
    duration_days: int
    avg_high_f: float
    avg_low_f: float
    avg_high_c: float
    avg_low_c: float
    rainy_days_count: int
    snowy_days_count: int
    max_precip_prob: int
    is_international: bool
    plug_types: str
    currency: str
    daily_forecasts: List[WeatherForecastDay] = field(default_factory=list)
    forecast_source: str = "live"  # "live" or "climate_estimate"

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class ClarifyingQuestion:
    id: str
    question: str
    reason: str
    suggested_options: List[str]
    default_option: str
    maps_to_tag: str


@dataclass
class PackingItem:
    id: Optional[int]
    trip_id: int
    name: str
    category: str
    quantity: int
    bag_location: str = "Carry-On"
    packed_outbound: bool = False
    packed_return: bool = False
    is_doorway_critical: bool = False
    is_high_loss_return: bool = False
    weather_reason: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


DEFAULT_TRAVELER_PROFILE: Dict[str, Any] = {
    "user_id": "default",
    "home_country_code": "US",
    "temperature_unit": "F",
    "laundry_cycle_days": 7,
    "repeat_pants_days": 3,
    "runs_cold": False,
    # 1. Generic Basics (Leave-the-House check)
    "always_pack_basics": [
        {
            "name": "House Keys",
            "category": CATEGORY_BASICS,
            "is_doorway_critical": True,
            "is_high_loss_return": True,
            "bag_location": "Personal Item",
        },
        {
            "name": "Payment Card / Cash",
            "category": CATEGORY_BASICS,
            "is_doorway_critical": True,
            "is_high_loss_return": True,
            "bag_location": "Personal Item",
        },
        {
            "name": "Sunglasses",
            "category": CATEGORY_BASICS,
            "is_doorway_critical": False,
            "is_high_loss_return": True,
            "bag_location": "Personal Item",
        },
        {
            "name": "Packing Cubes / Organizer Bags",
            "category": CATEGORY_PACKING_AIDS,
            "is_doorway_critical": False,
            "is_high_loss_return": True,
            "bag_location": "Carry-On",
        },
    ],
    # 2. Generic Documents
    "default_documents": [
        {
            "name": "Photo ID / Driver's License",
            "category": CATEGORY_DOCUMENTS,
            "is_doorway_critical": True,
            "is_high_loss_return": True,
            "bag_location": "Personal Item",
        },
        {
            "name": "Passport / Travel Document",
            "category": CATEGORY_DOCUMENTS,
            "is_doorway_critical": True,
            "is_high_loss_return": True,
            "bag_location": "Personal Item",
        },
        {
            "name": "Booking & Itinerary Confirmations",
            "category": CATEGORY_DOCUMENTS,
            "is_doorway_critical": True,
            "is_high_loss_return": False,
            "bag_location": "Personal Item",
        },
    ],
    # 3. Generic Tech & Chargers
    "default_tech": [
        {
            "name": "Primary Device Charger",
            "category": CATEGORY_TECH,
            "is_doorway_critical": True,
            "is_high_loss_return": True,
            "bag_location": "Personal Item",
            "notes": "Check wall outlet at checkout!",
        },
        {
            "name": "Portable Power Bank",
            "category": CATEGORY_TECH,
            "is_doorway_critical": False,
            "is_high_loss_return": True,
            "bag_location": "Personal Item",
        },
        {
            "name": "Headphones / Earbuds",
            "category": CATEGORY_TECH,
            "is_doorway_critical": False,
            "is_high_loss_return": True,
            "bag_location": "Personal Item",
        },
    ],
    # 4. Generic Toiletries & Health
    "default_toiletries": [
        {"name": "Toothbrush & Toothpaste", "category": CATEGORY_TOILETRIES, "is_high_loss_return": False},
        {"name": "Sunscreen & Skincare", "category": CATEGORY_TOILETRIES, "is_high_loss_return": False},
        {
            "name": "Personal First-Aid & Medication Kit",
            "category": CATEGORY_TOILETRIES,
            "is_doorway_critical": True,
            "is_high_loss_return": True,
        },
    ],
    # 5. Conditional Modules (Office/Work, Own Vehicle vs Rental Vehicle, International, Gym)
    "activity_modules": {
        "work": {
            "label": "Office / Work",
            "description": "Work equipment, access badge, and work essentials",
            "items": [
                {
                    "name": "Laptop",
                    "category": CATEGORY_OFFICE_WORK,
                    "is_doorway_critical": True,
                    "is_high_loss_return": True,
                    "bag_location": "Personal Item",
                },
                {
                    "name": "Work Device",
                    "category": CATEGORY_OFFICE_WORK,
                    "is_doorway_critical": True,
                    "is_high_loss_return": True,
                    "bag_location": "Personal Item",
                },
                {
                    "name": "Work ID / Access Badge",
                    "category": CATEGORY_OFFICE_WORK,
                    "is_doorway_critical": True,
                    "is_high_loss_return": True,
                    "bag_location": "Personal Item",
                },
                {
                    "name": "Work Device Charger",
                    "category": CATEGORY_OFFICE_WORK,
                    "is_doorway_critical": True,
                    "is_high_loss_return": True,
                    "bag_location": "Personal Item",
                },
            ],
        },
        "own_car": {
            "label": "Traveling by Own Vehicle",
            "description": "If traveling by personal vehicle -> adds Vehicle Registration & Insurance Docs",
            "items": [
                {
                    "name": "Vehicle Registration & Insurance Docs",
                    "category": CATEGORY_DOCUMENTS,
                    "is_doorway_critical": True,
                    "is_high_loss_return": True,
                    "bag_location": "Personal Item",
                },
            ],
        },
        "rental_car": {
            "label": "Rental Vehicle",
            "description": "If renting a vehicle -> adds Vehicle USB Data Cable + Corporate ID + Rental Reservation Docs",
            "items": [
                {
                    "name": "Vehicle USB Data Cable (Navigation / Audio)",
                    "category": CATEGORY_TECH,
                    "is_doorway_critical": True,
                    "is_high_loss_return": True,
                    "bag_location": "Personal Item",
                },
                {
                    "name": "Corporate / Membership ID (for Rental Rate)",
                    "category": CATEGORY_OFFICE_WORK,
                    "is_doorway_critical": True,
                    "is_high_loss_return": True,
                    "bag_location": "Personal Item",
                },
                {
                    "name": "Rental Vehicle Reservation Docs",
                    "category": CATEGORY_DOCUMENTS,
                    "is_doorway_critical": True,
                    "is_high_loss_return": False,
                    "bag_location": "Personal Item",
                },
            ],
        },
        "international": {
            "label": "International Travel",
            "description": "Power adapter and foreign currency",
            "items": [
                {
                    "name": "Universal Power Adapter",
                    "category": CATEGORY_TECH,
                    "is_doorway_critical": False,
                    "is_high_loss_return": True,
                    "bag_location": "Personal Item",
                },
            ],
        },
        "gym": {
            "label": "Gym / Workout",
            "description": "Extra workout shorts, t-shirts, and socks",
            "items": [
                {
                    "name": "Workout T-Shirts",
                    "category": CATEGORY_CLOTHES,
                    "scale_by_days": True,
                    "ratio_per_day": 0.5,
                    "extra_buffer": 0,
                },
                {
                    "name": "Workout Shorts",
                    "category": CATEGORY_CLOTHES,
                    "scale_by_days": True,
                    "ratio_per_day": 0.5,
                    "extra_buffer": 0,
                },
            ],
        },
    },
}
