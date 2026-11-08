"""PackTrack Rule & Conversational Gap-Analysis Engine."""

import math
import re
from typing import Dict, Any, List, Optional
from .models import (
    DestinationWeatherSummary,
    ClarifyingQuestion,
    CATEGORY_BASICS,
    CATEGORY_DOCUMENTS,
    CATEGORY_OFFICE_WORK,
    CATEGORY_CLOTHES,
    CATEGORY_TOILETRIES,
    CATEGORY_TECH,
    CATEGORY_PACKING_AIDS,
    CATEGORY_ACTIVITIES,
    DEFAULT_TRAVELER_PROFILE,
)


def generate_gap_questions(
    weather: DestinationWeatherSummary,
    profile: Dict[str, Any],
    initial_tags: Optional[List[str]] = None,
) -> List[Dict[str, Any]]:
    """Analyzes destination weather, duration, and user template to ask targeted questions."""
    tags = set(t.lower() for t in (initial_tags or []))
    questions: List[ClarifyingQuestion] = []

    # 1. Ground Transport rule
    if "own_car" not in tags and "rental_car" not in tags:
        questions.append(
            ClarifyingQuestion(
                id="car_mode",
                question="Are you driving your own vehicle, renting a vehicle, or using public transit?",
                reason="Adds Vehicle Registration/Insurance for own vehicle, or Navigation Cable & Rental Docs for a rental vehicle.",
                suggested_options=[
                    "Rental Vehicle (Navigation Cable + Rental Docs)",
                    "Driving Own Vehicle (Registration & Insurance)",
                    "No Vehicle / Public Transit",
                ],
                default_option="Rental Vehicle (Navigation Cable + Rental Docs)",
                maps_to_tag="rental_car",
            )
        )

    # 2. Purpose / Office & Work question
    if "work" not in tags and "leisure" not in tags:
        questions.append(
            ClarifyingQuestion(
                id="trip_purpose",
                question=f"Is this a work trip to {weather.destination_name}?",
                reason="Adds work equipment, access badge, and work charger.",
                suggested_options=["Yes, include Work Module", "Leisure only"],
                default_option="Yes, include Work Module",
                maps_to_tag="work",
            )
        )

    # 3. Weather-specific clothing confirmation (Thermals, Jacket, Raincoat, Shorts)
    questions.append(
        ClarifyingQuestion(
            id="weather_layers",
            question=(
                f"{weather.destination_name} forecast is {weather.avg_low_f}°F–{weather.avg_high_f}°F "
                f"with {weather.rainy_days_count} rainy days. Keep automatic weather selection for Thermals, Jacket, Raincoat & Shorts?"
            ),
            reason="Automatically scales T-shirts, Jeans, Shorts, Underwear, Socks, Thermals, Jacket, and Raincoat.",
            suggested_options=[
                "Yes, auto-pack based on weather",
                "Pack Thermals & Raincoat regardless of forecast",
            ],
            default_option="Yes, auto-pack based on weather",
            maps_to_tag="weather_auto",
        )
    )

    # 4. Laundry question for trips >= 5 days
    if weather.duration_days >= 5:
        questions.append(
            ClarifyingQuestion(
                id="laundry_plan",
                question=f"For this {weather.duration_days}-day trip, will you do laundry or pack clothes for all {weather.duration_days} days?",
                reason="Adjusts T-shirts, Underwear, Socks, Jeans, and Shorts counts.",
                suggested_options=[
                    f"No laundry (pack for all {weather.duration_days} days)",
                    "Yes, laundry mid-trip (cap at 5 days)",
                ],
                default_option=f"No laundry (pack for all {weather.duration_days} days)",
                maps_to_tag="laundry",
            )
        )

    return [
        {
            "id": q.id,
            "question": q.question,
            "reason": q.reason,
            "suggested_options": q.suggested_options,
            "default_option": q.default_option,
            "maps_to_tag": q.maps_to_tag,
        }
        for q in questions
    ]


def calculate_weather_clothes(
    weather: DestinationWeatherSummary,
    effective_days: int,
    repeat_pants_days: int = 3,
    is_work_trip: bool = False,
    runs_cold: bool = False,
    rain_preference: str = "Raincoat",
) -> List[Dict[str, Any]]:
    """Calculates clothing list based on weather & duration."""
    clothes: List[Dict[str, Any]] = []
    days = max(1, weather.duration_days)
    pack_days = max(1, min(days, effective_days))

    temp_shift = -5.0 if runs_cold else 0.0
    high_f = weather.avg_high_f + temp_shift
    low_f = weather.avg_low_f + temp_shift

    # 1. Core daily clothes: T-shirts, Underwear, Socks
    clothes.append({
        "name": "T-Shirts",
        "category": CATEGORY_CLOTHES,
        "quantity": pack_days + 1,
        "weather_reason": f"{pack_days} days + 1 spare ({weather.avg_high_f}°F avg high)",
    })
    clothes.append({
        "name": "Underwear",
        "category": CATEGORY_CLOTHES,
        "quantity": pack_days + 1,
        "weather_reason": f"{pack_days} days + 1 spare",
    })
    clothes.append({
        "name": "Socks",
        "category": CATEGORY_CLOTHES,
        "quantity": pack_days + 1,
        "weather_reason": f"{pack_days} days + 1 spare",
    })

    # 2. Jeans & Shorts (scaled by duration and temperature)
    total_bottoms = max(2, math.ceil(pack_days / max(1, repeat_pants_days)))
    if high_f >= 72:
        jeans_qty = max(1, total_bottoms // 2)
        shorts_qty = max(2, pack_days // 2)
        shorts_reason = f"Warm daytime highs ({weather.avg_high_f}°F) + indoor/sleep"
    else:
        jeans_qty = total_bottoms
        shorts_qty = max(1, math.ceil(pack_days / 4))
        shorts_reason = "Indoor / hotel lounging & sleep"

    clothes.append({
        "name": "Jeans",
        "category": CATEGORY_CLOTHES,
        "quantity": jeans_qty,
        "weather_reason": f"1 pair per {repeat_pants_days} days",
    })
    clothes.append({
        "name": "Shorts",
        "category": CATEGORY_CLOTHES,
        "quantity": shorts_qty,
        "weather_reason": shorts_reason,
    })

    # 3. Footwear & accessories: Belt, Shoes, Slippers
    clothes.append({
        "name": "Belt",
        "category": CATEGORY_CLOTHES,
        "quantity": 1,
        "is_high_loss_return": True,
        "weather_reason": "Daily wear",
    })
    clothes.append({
        "name": "Shoes",
        "category": CATEGORY_CLOTHES,
        "quantity": 1,
        "bag_location": "Worn",
        "is_doorway_critical": True,
        "weather_reason": "Primary travel & walking shoes",
    })
    clothes.append({
        "name": "Slippers",
        "category": CATEGORY_CLOTHES,
        "quantity": 1,
        "is_high_loss_return": True,
        "weather_reason": "Hotel room / indoor comfort",
    })

    # 4. Weather-triggered layers: Jacket, Thermals, Raincoat
    if low_f <= 65:
        clothes.append({
            "name": "Jacket",
            "category": CATEGORY_CLOTHES,
            "quantity": 1,
            "bag_location": "Worn",
            "is_high_loss_return": True,
            "weather_reason": f"Forecast lows around {weather.avg_low_f}°F",
        })

    if low_f <= 50:
        thermals_qty = 1 if pack_days <= 3 else 2
        clothes.append({
            "name": "Thermals",
            "category": CATEGORY_CLOTHES,
            "quantity": thermals_qty,
            "weather_reason": f"Cold forecast (lows {weather.avg_low_f}°F)",
        })

    if weather.rainy_days_count > 0 or weather.max_precip_prob >= 35 or "rain" in rain_preference.lower():
        clothes.append({
            "name": "Raincoat",
            "category": CATEGORY_CLOTHES,
            "quantity": 1,
            "is_high_loss_return": True,
            "weather_reason": f"Rain expected ({weather.rainy_days_count} days, {weather.max_precip_prob}% max chance)",
        })

    return clothes


def build_complete_checklist(
    trip_id: int,
    weather: DestinationWeatherSummary,
    profile: Optional[Dict[str, Any]] = None,
    selected_tags: Optional[List[str]] = None,
    do_laundry: bool = False,
    rain_preference: str = "Raincoat",
    custom_extra_items: Optional[List[Dict[str, Any]]] = None,
    custom_rules: Optional[List[Dict[str, Any]]] = None,
) -> List[Dict[str, Any]]:
    """Synthesizes a complete categorized checklist from Profile Templates + Weather + Transport Mode."""
    prof = profile or DEFAULT_TRAVELER_PROFILE
    tags = set(t.lower() for t in (selected_tags or []))
    if weather.is_international:
        tags.add("international")

    if "own_car" not in tags and "no_car" not in tags and "rental_car" not in tags:
        tags.add("rental_car")

    laundry_cycle = 5 if do_laundry else prof.get("laundry_cycle_days", 14)
    effective_days = min(weather.duration_days, laundry_cycle) if do_laundry else weather.duration_days
    is_work = "work" in tags or "office" in tags or "business" in tags
    is_personal_car = "own_car" in tags or "personal_car" in tags
    is_flight = not is_personal_car

    items: List[Dict[str, Any]] = []
    seen_names = set()

    def _add_item(
        name: str,
        category: str,
        quantity: int = 1,
        bag_location: str = "Carry-On",
        is_doorway_critical: bool = False,
        is_high_loss_return: bool = False,
        weather_reason: str = "",
    ):
        key = name.strip().lower()
        if key in seen_names:
            return
        seen_names.add(key)
        items.append({
            "trip_id": trip_id,
            "name": name,
            "category": category,
            "quantity": max(1, int(quantity)),
            "bag_location": bag_location,
            "packed_outbound": False,
            "packed_return": False,
            "is_doorway_critical": bool(is_doorway_critical),
            "is_high_loss_return": bool(is_high_loss_return),
            "weather_reason": weather_reason,
        })

    # 1. Always-Pack Basics & Packing Aids
    for b in prof.get("always_pack_basics", []):
        _add_item(
            name=b["name"],
            category=b.get("category", CATEGORY_BASICS),
            quantity=1,
            bag_location=b.get("bag_location", "Personal Item"),
            is_doorway_critical=b.get("is_doorway_critical", False),
            is_high_loss_return=b.get("is_high_loss_return", True),
            weather_reason="Template essential",
        )

    # 2. Documents
    for doc in prof.get("default_documents", []):
        _add_item(
            name=doc["name"],
            category=doc.get("category", CATEGORY_DOCUMENTS),
            quantity=1,
            bag_location=doc.get("bag_location", "Personal Item"),
            is_doorway_critical=doc.get("is_doorway_critical", True),
            is_high_loss_return=doc.get("is_high_loss_return", False),
            weather_reason="Required travel document",
        )

    # 3. Conditional Modules (Office/Work, Own Vehicle vs Rental Vehicle, International, Gym)
    activity_modules = prof.get("activity_modules", {})
    for tag in tags:
        mod = activity_modules.get(tag)
        if not mod:
            continue
        for rule in mod.get("items", []):
            qty = 1
            if rule.get("scale_by_days"):
                ratio = float(rule.get("ratio_per_day", 0.5))
                qty = max(1, math.ceil(effective_days * ratio) + int(rule.get("extra_buffer", 0)))
            reason = mod.get("label", tag.title())
            item_name = rule["name"]
            if tag == "international" and "Adapter" in item_name:
                item_name = f"Power Adapter ({weather.plug_types})"
                reason = f"Required for {weather.country} outlets"

            _add_item(
                name=item_name,
                category=rule.get("category", CATEGORY_ACTIVITIES),
                quantity=qty,
                bag_location=rule.get("bag_location", "Carry-On"),
                is_doorway_critical=rule.get("is_doorway_critical", False),
                is_high_loss_return=rule.get("is_high_loss_return", False),
                weather_reason=reason,
            )

    # 4. Weather-Calculated Clothes
    weather_clothes = calculate_weather_clothes(
        weather=weather,
        effective_days=effective_days,
        repeat_pants_days=prof.get("repeat_pants_days", 3),
        is_work_trip=is_work,
        runs_cold=prof.get("runs_cold", False),
        rain_preference=rain_preference,
    )
    for c in weather_clothes:
        _add_item(
            name=c["name"],
            category=c["category"],
            quantity=c["quantity"],
            bag_location=c.get("bag_location", "Carry-On"),
            is_doorway_critical=c.get("is_doorway_critical", False),
            is_high_loss_return=c.get("is_high_loss_return", False),
            weather_reason=c.get("weather_reason", ""),
        )

    # 5. Tech & Chargers
    for tech in prof.get("default_tech", []):
        _add_item(
            name=tech["name"],
            category=tech.get("category", CATEGORY_TECH),
            quantity=1,
            bag_location=tech.get("bag_location", "Personal Item"),
            is_doorway_critical=tech.get("is_doorway_critical", False),
            is_high_loss_return=tech.get("is_high_loss_return", True),
            weather_reason=tech.get("notes", "Tech essential"),
        )

    # 6. Toiletries & Health
    for toil in prof.get("default_toiletries", []):
        _add_item(
            name=toil["name"],
            category=toil.get("category", CATEGORY_TOILETRIES),
            quantity=1,
            bag_location="Carry-On",
            is_doorway_critical=toil.get("is_doorway_critical", False),
            is_high_loss_return=toil.get("is_high_loss_return", False),
            weather_reason="Health & personal care",
        )

    # 7. Conditional custom_rules (from argument or profile["custom_rules"])
    nl_rule_re = re.compile(r"^if\s+(.+?),\s*(.+?)\s+should\s+be\s+added\.?$", re.IGNORECASE)
    rules_to_eval = custom_rules if custom_rules is not None else prof.get("custom_rules", [])
    for rule in (rules_to_eval or []):
        raw_name = str(rule.get("name", "")).strip()
        raw_mod = str(rule.get("moduleKey", rule.get("condition", "Always"))).strip()
        m = nl_rule_re.match(raw_name)
        if m and (not raw_mod or raw_mod.lower() == "always"):
            raw_mod = m.group(1).strip()
            effective_name = m.group(2).strip()
            if effective_name and effective_name[0].islower():
                effective_name = effective_name[0].upper() + effective_name[1:]
        else:
            effective_name = raw_name

        cond_lower = raw_mod.lower()
        should_include = evaluate_rule_condition(
            cond_lower=cond_lower,
            days=weather.duration_days,
            is_work=is_work,
            is_personal_car=is_personal_car,
            is_flight=is_flight,
            tags=tags,
            weather=weather,
        )

        if not should_include:
            continue

        qty = 1
        if rule.get("scaleByDays") or rule.get("scale_by_days"):
            ratio = float(rule.get("ratioPerDay", rule.get("ratio_per_day", 1.0)) or 1.0)
            qty = max(1, math.ceil(effective_days * ratio))

        _add_item(
            name=effective_name,
            category=rule.get("category", CATEGORY_BASICS),
            quantity=qty,
            bag_location=rule.get("bagLocation", rule.get("bag_location", "Carry-On")),
            is_doorway_critical=rule.get("isDoorwayCritical", rule.get("is_doorway_critical", False)),
            is_high_loss_return=rule.get("isHighLossReturn", rule.get("is_high_loss_return", True)),
            weather_reason=raw_mod if raw_mod.lower() != "always" else "",
        )

    # 8. Any custom user-requested items for this specific trip
    for extra in (custom_extra_items or []):
        _add_item(
            name=extra["name"],
            category=extra.get("category", CATEGORY_BASICS),
            quantity=extra.get("quantity", 1),
            is_doorway_critical=extra.get("is_doorway_critical", False),
            is_high_loss_return=extra.get("is_high_loss_return", True),
            weather_reason=extra.get("weather_reason", "Custom addition"),
        )

    return items


def evaluate_rule_condition(
    cond_lower: str,
    days: int,
    is_work: bool,
    is_personal_car: bool,
    is_flight: bool,
    tags: set,
    weather: DestinationWeatherSummary,
) -> bool:
    """Evaluates all compound constraints present in a natural-language rule condition (AND semantics)."""
    cond_lower = cond_lower.strip().lower()
    if not cond_lower or cond_lower in ("always", "default"):
        return True

    if "personal car" in cond_lower or "own car" in cond_lower or cond_lower in ("own_car", "personal_car"):
        if not is_personal_car:
            return False
    if "rental" in cond_lower or cond_lower == "rental_car":
        if "rental_car" not in tags:
            return False
    if "flight" in cond_lower or "fly" in cond_lower or cond_lower in ("carry_on", "checked_bag"):
        if not is_flight:
            return False

    if "work" in cond_lower or "office" in cond_lower or "business" in cond_lower:
        if not is_work:
            return False

    if "international" in cond_lower:
        if not weather.is_international:
            return False

    if re.search(r"\b(rain|rainy|wet|drizzle)\b", cond_lower):
        m_rain = re.search(r"(?:more than|at\s*least|>=?)\s*(\d+)\s*rain", cond_lower)
        min_rain = int(m_rain.group(1)) if m_rain else 1
        if weather.rainy_days_count < min_rain:
            return False
    if re.search(r"\b(cold|winter|freezing|chilly)\b", cond_lower):
        if weather.avg_low_f > 58.0:
            return False
    if re.search(r"\b(snow|snowy|blizzard)\b", cond_lower):
        if weather.snowy_days_count <= 0 and weather.avg_low_f > 32.0:
            return False
    if re.search(r"\b(sun|sunny|warm|hot)\b", cond_lower):
        if weather.avg_high_f < 68.0 and weather.rainy_days_count > 0:
            return False

    m_more = re.search(r"(?:more than|greater than|over|>)\s*(\d+)\s*day", cond_lower)
    if m_more and days <= int(m_more.group(1)):
        return False

    m_min = re.search(r"(?:at\s*least|minimum|>=)\s*(\d+)\s*day", cond_lower)
    if m_min and days < int(m_min.group(1)):
        return False

    m_less = re.search(r"(?:less than|under|fewer than|<)\s*(\d+)\s*day", cond_lower)
    if m_less and days >= int(m_less.group(1)):
        return False

    dest_re = re.compile(
        r"\b(?:go(?:ing)?\s+to|trip\s+to|travel(?:ing)?\s+to|visit(?:ing)?|in)\s+([a-z][a-z\s]{1,24}?)(?=\s+(?:and|or|if|when|for|with|$)|[,.]|$)"
    )
    ignore_tokens = {
        "personal car", "own car", "rental car", "a car", "car", "flight", "plane",
        "work", "office", "rain", "cold", "winter", "summer", "the beach", "hotel",
    }
    for m_dest in dest_re.finditer(cond_lower):
        candidate = m_dest.group(1).strip()
        if candidate not in ignore_tokens and not candidate.endswith("days") and not candidate.endswith("day"):
            city_lower = weather.destination_name.lower()
            country_lower = weather.country.lower()
            code_lower = weather.country_code.lower()
            if not (candidate in city_lower or candidate in country_lower or candidate == code_lower):
                return False

    return True
