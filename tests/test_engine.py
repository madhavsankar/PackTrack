"""Unit tests for PackTrack Weather, Gap Questions, Starter Template, DB, and Gemini Voice Agent."""

import os
import tempfile
import unittest
from packtrack.weather import fetch_destination_weather
from packtrack.engine import generate_gap_questions, build_complete_checklist
from packtrack.db import PackTrackDB
from packtrack.gemini_agent import GeminiVoiceAgent


class TestPackTrackEngine(unittest.TestCase):
    def setUp(self):
        self.tmp_db = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        self.tmp_db.close()
        self.db = PackTrackDB(self.tmp_db.name)

    def tearDown(self):
        if os.path.exists(self.tmp_db.name):
            os.remove(self.tmp_db.name)

    def test_weather_and_international_detection(self):
        weather = fetch_destination_weather("London", duration_days=5, home_country_code="US")
        self.assertEqual(weather.destination_name, "London")
        self.assertTrue(weather.is_international)
        self.assertIn("Type G", weather.plug_types)
        self.assertEqual(len(weather.daily_forecasts), 5)

    def test_template_categories_and_vehicle_rules(self):
        profile = self.db.get_profile()
        weather = fetch_destination_weather("Seattle", duration_days=6, home_country_code="US")
        questions = generate_gap_questions(weather, profile, initial_tags=[])
        q_ids = [q["id"] for q in questions]
        self.assertIn("car_mode", q_ids)
        self.assertIn("trip_purpose", q_ids)

        # Case A: Rental Vehicle + Work Trip
        items_rental = build_complete_checklist(
            trip_id=1,
            weather=weather,
            profile=profile,
            selected_tags=["work", "rental_car"],
            do_laundry=False,
        )
        names_rental = {i["name"] for i in items_rental}
        self.assertIn("House Keys", names_rental)
        self.assertIn("Payment Card / Cash", names_rental)
        self.assertIn("Photo ID / Driver's License", names_rental)
        self.assertIn("Passport / Travel Document", names_rental)
        self.assertIn("Booking & Itinerary Confirmations", names_rental)
        self.assertIn("Vehicle USB Data Cable (Navigation / Audio)", names_rental)
        self.assertIn("Primary Device Charger", names_rental)
        self.assertIn("Work Device", names_rental)
        self.assertIn("Work ID / Access Badge", names_rental)
        self.assertIn("Packing Cubes / Organizer Bags", names_rental)
        self.assertIn("Personal First-Aid & Medication Kit", names_rental)
        self.assertNotIn("Vehicle Registration & Insurance Docs", names_rental)

        # Case B: Own Vehicle Trip -> Registration & Insurance added instead of Rental Cable
        items_own_car = build_complete_checklist(
            trip_id=2,
            weather=weather,
            profile=profile,
            selected_tags=["own_car"],
            do_laundry=False,
        )
        names_own = {i["name"] for i in items_own_car}
        self.assertIn("Vehicle Registration & Insurance Docs", names_own)
        self.assertNotIn("Vehicle USB Data Cable (Navigation / Audio)", names_own)

        # Verify Underwear, Socks, and T-Shirts scale to duration + 1 (6 + 1 = 7)
        underwear = next(i for i in items_rental if i["name"] == "Underwear")
        tshirts = next(i for i in items_rental if i["name"] == "T-Shirts")
        self.assertEqual(underwear["quantity"], 7)
        self.assertEqual(tshirts["quantity"], 7)

    def test_voice_agent_workflow_creates_and_checks_items(self):
        agent = GeminiVoiceAgent()
        # Step 1: User speaks destination + duration
        turn1 = agent.handle_turn("I'm going to Tokyo for 6 days", session_id="test_voice")
        self.assertIn("Tokyo", turn1["reply"])

        # Step 2: User answers clarifying question ("work and rental car")
        turn2 = agent.handle_turn("It's for work with a rental car", session_id="test_voice")
        self.assertIsNotNone(turn2["updated_trip"])
        trip_id = turn2["updated_trip"]["id"]

        # Step 3: User checks off items by voice
        turn3 = agent.handle_turn(
            "Mark keys, passport, and charger packed",
            session_id="test_voice",
            active_trip_id=trip_id,
        )
        packed_items = [i["name"] for i in turn3["updated_trip"]["items"] if i["packed_outbound"]]
        self.assertTrue(any("Keys" in n for n in packed_items))
        self.assertTrue(any("Passport" in n for n in packed_items))
        self.assertTrue(any("Charger" in n for n in packed_items))

    def test_eval_work_selected_adds_laptop_to_final_list(self):
        """Eval: Whenever work is selected and a rule states 'if work is expcted to be done', Laptop must be in final list."""
        weather = fetch_destination_weather("Seattle", duration_days=4, home_country_code="US")
        condition_variants = [
            "if work is expcted to be done",
            "if work is expected to be done",
            "Only when working",
            "work",
        ]

        for cond in condition_variants:
            custom_rules = [
                {"moduleKey": "Always", "name": "Phones", "category": "On Me"},
                {
                    "moduleKey": cond,
                    "name": "Laptop",
                    "category": "Electronics",
                    "isDoorwayCritical": True,
                    "bagLocation": "Personal",
                },
            ]

            # When work is selected -> Laptop must be in final list
            items_work = build_complete_checklist(
                trip_id=100,
                weather=weather,
                profile={"custom_rules": custom_rules},
                selected_tags=["work", "rental_car"],
            )
            names_work_lower = {i["name"].lower() for i in items_work}
            self.assertIn(
                "laptop",
                names_work_lower,
                f"Expected 'Laptop' in final list when work is selected for rule condition '{cond}'",
            )

            # When leisure is selected (work not selected) -> Laptop must NOT be in final list
            items_leisure = build_complete_checklist(
                trip_id=101,
                weather=weather,
                profile={"custom_rules": custom_rules},
                selected_tags=["leisure", "rental_car"],
            )
            names_leisure_lower = {i["name"].lower() for i in items_leisure}
            self.assertNotIn(
                "laptop",
                names_leisure_lower,
                f"Expected 'Laptop' to be excluded when work is NOT selected for rule condition '{cond}'",
            )

        # Also verify single-string natural-language rule: "if work is expcted to be done, laptop should be added"
        nl_rules = [
            {
                "moduleKey": "Always",
                "name": "if work is expcted to be done, laptop should be added",
                "category": "Electronics",
            }
        ]
        items_nl_work = build_complete_checklist(
            trip_id=102,
            weather=weather,
            profile={"custom_rules": nl_rules},
            selected_tags=["work"],
        )
        self.assertIn("laptop", {i["name"].lower() for i in items_nl_work})

        items_nl_leisure = build_complete_checklist(
            trip_id=103,
            weather=weather,
            profile={"custom_rules": nl_rules},
            selected_tags=["leisure"],
        )
        self.assertNotIn("laptop", {i["name"].lower() for i in items_nl_leisure})

    def test_eval_cloudflare_packing_engine_work_laptop_rule(self):
        """Eval: Verifies Cloudflare PackingEngineService adds Laptop when work is selected and rule says 'if work is expcted to be done'."""
        import json
        import shutil
        import subprocess

        root_dir = os.path.dirname(os.path.dirname(__file__))
        engine_js_path = os.path.join(root_dir, "cloudflare", "src", "services", "PackingEngineService.js")
        with open(engine_js_path, "r", encoding="utf-8") as f:
            engine_src = f.read().replace("export class PackingEngineService", "class PackingEngineService")

        eval_harness = (
            engine_src
            + """
        const weather = {
          city: 'Seattle',
          country: 'United States',
          countryCode: 'US',
          avgHighF: 64,
          avgLowF: 49,
          rainyDaysCount: 2,
          plugTypes: 'Type A/B',
          isInternational: false,
          dailyBreakdown: ['Day 1: 49°F–64°F, Rain']
        };
        const rules = [
          { moduleKey: 'Always', name: 'Phones', category: 'On Me' },
          { moduleKey: 'if work is expcted to be done', name: 'Laptop', category: 'Electronics' },
          { moduleKey: 'Always', name: 'if work is expected to be done, laptop charger should be added', category: 'Electronics' }
        ];
        const workItems = PackingEngineService.buildTripChecklist({
          weather,
          durationDays: 4,
          tags: ['work', 'rental_car', 'carry_on'],
          customRules: rules
        });
        const leisureItems = PackingEngineService.buildTripChecklist({
          weather,
          durationDays: 4,
          tags: ['leisure', 'rental_car', 'carry_on'],
          customRules: rules
        });
        const out = JSON.stringify({
          workNames: workItems.map(i => i.name),
          leisureNames: leisureItems.map(i => i.name)
        });
        if (typeof console !== 'undefined' && console.log) console.log(out);
        else if (typeof print !== 'undefined') print(out);
        """
        )

        node_bin = shutil.which("node")
        jsc_bin = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc"
        cmd = [node_bin, "-e", eval_harness] if node_bin else [jsc_bin, "-e", eval_harness]
        proc = subprocess.run(cmd, cwd=root_dir, capture_output=True, text=True, check=True)
        data = json.loads(proc.stdout.strip().splitlines()[-1])
        self.assertIn("Laptop", data["workNames"])
        self.assertIn("Laptop charger", data["workNames"])
        self.assertNotIn("Laptop", data["leisureNames"])
        self.assertNotIn("Laptop charger", data["leisureNames"])

    def test_eval_gemini_request_and_response_schema_validation(self):
        """Eval: Verifies the outbound Gemini request payload schema and inbound response parser/validator."""
        import json
        import shutil
        import subprocess

        root_dir = os.path.dirname(os.path.dirname(__file__))
        engine_js_path = os.path.join(root_dir, "cloudflare", "src", "services", "PackingEngineService.js")
        with open(engine_js_path, "r", encoding="utf-8") as f:
            engine_src = f.read().replace("export class PackingEngineService", "class PackingEngineService")

        eval_harness = (
            engine_src
            + """
        const weather = {
          city: 'Seattle',
          country: 'United States',
          countryCode: 'US',
          avgHighF: 64,
          avgLowF: 49,
          rainyDaysCount: 2,
          plugTypes: 'Type A/B',
          isInternational: false,
          dailyBreakdown: ['Day 1 (2026-11-08): 48°F–61°F, Rain (80% precip)']
        };
        const templateItems = [
          { name: 'Laptop', quantity: 1, category: 'Electronics' },
          { name: 'Phones', quantity: 1, category: 'On Me' },
          { name: 'DSLR Body', quantity: 1, category: 'Camera Gear' }
        ];
        const reqPayload = PackingEngineService.buildGeminiSuggestionRequestPayload({
          weather,
          durationDays: 4,
          tags: ['work', 'rental_car'],
          templateItems,
          customCategories: ['Gym']
        });

        const mockGeminiApiResponse = {
          candidates: [{
            content: {
              parts: [{
                text: JSON.stringify([
                  { name: 'Laptop', category: 'Electronics', quantity: 1, bagLocation: 'Personal', reason: 'Duplicate of template item' },
                  { name: 'Rain Cover for Lens', category: 'Camera Gear', quantity: 1, bagLocation: 'Carry-On', reason: 'We expect rain on Day 1 (80% precip)' },
                  { name: 'Warm Fleece', category: 'InvalidCategory', quantity: -3, bagLocation: 'InvalidBag', reason: '[SUGGESTED] Chilly 48°F low' }
                ])
              }]
            }
          }]
        };
        const parsed = PackingEngineService.parseGeminiSuggestionResponse(mockGeminiApiResponse, {
          existingNames: new Set(['laptop', 'phones', 'dslr body']),
          city: 'Seattle',
          allowedCategories: ['Camera Gear', 'Gym']
        });

        const out = JSON.stringify({ reqPayload, parsed });
        if (typeof console !== 'undefined' && console.log) console.log(out);
        else if (typeof print !== 'undefined') print(out);
        """
        )

        node_bin = shutil.which("node")
        jsc_bin = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc"
        cmd = [node_bin, "-e", eval_harness] if node_bin else [jsc_bin, "-e", eval_harness]
        proc = subprocess.run(cmd, cwd=root_dir, capture_output=True, text=True, check=True)
        result = json.loads(proc.stdout.strip().splitlines()[-1])

        req = result["reqPayload"]
        self.assertEqual(req["generationConfig"]["responseMimeType"], "application/json")
        schema = req["generationConfig"]["responseSchema"]
        self.assertEqual(schema["type"], "ARRAY")
        self.assertEqual(
            set(schema["items"]["required"]),
            {"name", "category", "quantity", "bagLocation", "reason"},
        )
        # Custom categories ('Camera Gear', 'Gym') must be dynamically allowed in the responseSchema enum
        cat_enum = schema["items"]["properties"]["category"]["enum"]
        self.assertIn("Camera Gear", cat_enum)
        self.assertIn("Gym", cat_enum)

        parsed = result["parsed"]
        # 'Laptop' duplicate must be filtered out; 2 valid suggestions remain
        self.assertEqual(len(parsed), 2)
        self.assertEqual(parsed[0]["name"], "Rain Cover for Lens")
        self.assertEqual(parsed[0]["category"], "Camera Gear")
        self.assertEqual(parsed[0]["bagLocation"], "Carry-On")
        self.assertTrue(parsed[0]["weatherReason"].startswith("[SUGGESTED] "))

        # Invalid category/bag/quantity are normalized safely
        self.assertEqual(parsed[1]["name"], "Warm Fleece")
        self.assertEqual(parsed[1]["category"], "Others")
        self.assertEqual(parsed[1]["bagLocation"], "Carry-On")
        self.assertEqual(parsed[1]["quantity"], 1)
        self.assertEqual(parsed[1]["weatherReason"], "[SUGGESTED] Chilly 48°F low")

    def test_eval_complex_compound_rule_rain_more_than_5_days_and_india(self):
        """Eval: Verifies compound rule 'Only add this when there is atleast one rainy and and if trip is for more than 5 days and n=only when we go to India'."""
        from packtrack.models import DestinationWeatherSummary

        complex_cond = (
            "Only add this when there is atleast one rainy and and if trip is for more than 5 days "
            "and n=only when we go to India"
        )
        rules = [
            {
                "moduleKey": complex_cond,
                "name": "Monsoon Trekking Poncho",
                "category": "Clothes",
            }
        ]

        india_rainy_7d = DestinationWeatherSummary(
            destination_name="Mumbai",
            country="India",
            country_code="IN",
            latitude=19.07,
            longitude=72.87,
            start_date="2026-11-10",
            duration_days=7,
            avg_high_f=86.0,
            avg_low_f=74.0,
            avg_high_c=30.0,
            avg_low_c=23.0,
            rainy_days_count=2,
            snowy_days_count=0,
            max_precip_prob=80,
            is_international=True,
            plug_types="Type C/D/M",
            currency="INR",
        )

        # 1. All 3 conditions met (India + 7 days > 5 + 2 rainy days >= 1) -> Included
        items_match = build_complete_checklist(
            trip_id=200,
            weather=india_rainy_7d,
            profile={"custom_rules": rules},
            selected_tags=["leisure"],
        )
        self.assertIn("Monsoon Trekking Poncho", {i["name"] for i in items_match})

        # 2. Only 4 days (fails 'more than 5 days') -> Excluded
        india_rainy_4d = DestinationWeatherSummary(**{**india_rainy_7d.to_dict(), "duration_days": 4})
        items_short = build_complete_checklist(
            trip_id=201,
            weather=india_rainy_4d,
            profile={"custom_rules": rules},
            selected_tags=["leisure"],
        )
        self.assertNotIn("Monsoon Trekking Poncho", {i["name"] for i in items_short})

        # 3. 0 rainy days (fails 'atleast one rainy') -> Excluded
        india_dry_7d = DestinationWeatherSummary(**{**india_rainy_7d.to_dict(), "rainy_days_count": 0})
        items_dry = build_complete_checklist(
            trip_id=202,
            weather=india_dry_7d,
            profile={"custom_rules": rules},
            selected_tags=["leisure"],
        )
        self.assertNotIn("Monsoon Trekking Poncho", {i["name"] for i in items_dry})

        # 4. Destination is Seattle, US (fails 'when we go to India') -> Excluded
        us_rainy_7d = DestinationWeatherSummary(
            **{
                **india_rainy_7d.to_dict(),
                "destination_name": "Seattle",
                "country": "United States",
                "country_code": "US",
            }
        )
        items_us = build_complete_checklist(
            trip_id=203,
            weather=us_rainy_7d,
            profile={"custom_rules": rules},
            selected_tags=["leisure"],
        )
        self.assertNotIn("Monsoon Trekking Poncho", {i["name"] for i in items_us})

    def test_eval_generalized_model_cascade_discovery_and_failover(self):
        """Eval: Verifies generalized model ranking (e.g. future 4.3 and 3.2 models) and automatic cascade failover."""
        import json
        import shutil
        import subprocess

        root_dir = os.path.dirname(os.path.dirname(__file__))
        engine_js_path = os.path.join(root_dir, "cloudflare", "src", "services", "PackingEngineService.js")
        with open(engine_js_path, "r", encoding="utf-8") as f:
            engine_src = f.read().replace("export class PackingEngineService", "class PackingEngineService")

        eval_harness = (
            engine_src
            + """
        (async () => {
          const mockModelsApi = [
            { name: 'models/text-embedding-004', supportedGenerationMethods: ['embedContent'] },
            { name: 'models/gemma-4-31b-it', supportedGenerationMethods: ['generateContent'] },
            { name: 'models/gemini-3.1-flash-image', supportedGenerationMethods: ['generateContent'] },
            { name: 'models/gemini-3-pro-image', supportedGenerationMethods: ['generateContent'] },
            { name: 'models/gemini-omni-1.1-flash', supportedGenerationMethods: ['generateContent'] },
            { name: 'models/gemini-2.5-flash', supportedGenerationMethods: ['generateContent'] },
            { name: 'models/gemini-4-argon', supportedGenerationMethods: ['generateContent'] },
            { name: 'models/gemini-3.2-flash-lite', supportedGenerationMethods: ['generateContent'] },
            { name: 'models/gemini-4.3-flash', supportedGenerationMethods: ['generateContent'] },
            { name: 'models/gemini-3.8-flash-tts', supportedGenerationMethods: ['generateContent'] }
          ];
          const ranked = PackingEngineService.rankModelsFromList(mockModelsApi);

          // Simulate cascade failover: 1st model ('gemini-4.3-flash') returns 429 rate-limit,
          // next models fail until 'gemini-3.2-flash-lite' succeeds with 200 OK!
          const attempted = [];
          const mockFetch = async (url) => {
            const m = url.match(/models\\/([^:?]+):generateContent/);
            const modelId = m ? decodeURIComponent(m[1]) : 'unknown';
            attempted.push(modelId);
            if (modelId === 'gemini-3.2-flash-lite') {
              return {
                ok: true,
                json: async () => ({
                  candidates: [{ content: { parts: [{ text: '[{"name":"Umbrella","category":"Others","quantity":1,"bagLocation":"Carry-On","reason":"Rain expected"}]' }] } }]
                })
              };
            }
            return { ok: false, status: 429 };
          };

          const res = await PackingEngineService.callGeminiWithModelCascade(
            { contents: [] },
            'test-key',
            { fetchImpl: mockFetch, cascadeOverride: ranked }
          );

          // Simulate transient failure on attempt 1 (truncated JSON) followed by success on retry 2 of same model
          let retryCount = 0;
          const retryMockFetch = async () => {
            retryCount++;
            if (retryCount === 1) {
              return {
                ok: true,
                json: async () => ({
                  candidates: [{ content: { parts: [{ text: '[{"name":"Truncated' }] } }]
                })
              };
            }
            return {
              ok: true,
              json: async () => ({
                candidates: [{ content: { parts: [{ text: '```json\\n[{"name":"Raincoat","category":"Clothes","quantity":1,"bagLocation":"Carry-On","reason":"Rain expected"}]\\n```' }] } }]
              })
            };
          };
          const retryRes = await PackingEngineService.callGeminiWithModelCascade(
            { contents: [] },
            'test-key',
            { fetchImpl: retryMockFetch, cascadeOverride: ['gemini-3.8-flash'] }
          );

          // Verify plug info needed only for non-USA and non-India
          const plugUs = PackingEngineService.isPlugInfoNeeded('United States', 'US', 'Type A/B');
          const plugIn = PackingEngineService.isPlugInfoNeeded('India', 'IN', 'Type C/D/M');
          const plugGb = PackingEngineService.isPlugInfoNeeded('United Kingdom', 'GB', 'Type G');
          const plugJp = PackingEngineService.isPlugInfoNeeded('Japan', 'JP', 'Type A/B');

          const out = JSON.stringify({
            firstModel: ranked[0],
            ranked,
            hasNonTextOrLegacy: ranked.some(id =>
              ['text-embedding-004', 'gemma-4-31b-it', 'gemini-3.1-flash-image', 'gemini-3-pro-image', 'gemini-omni-1.1-flash', 'gemini-2.5-flash', 'gemini-3.8-flash-tts'].includes(id)
            ),
            attempted,
            modelUsed: res ? res.modelUsed : null,
            retryAttempts: retryRes ? retryRes.attemptsOnModel : 0,
            retryModelUsed: retryRes ? retryRes.modelUsed : null,
            plugUs,
            plugIn,
            plugGb,
            plugJp
          });
          if (typeof console !== 'undefined' && console.log) console.log(out);
          else if (typeof print !== 'undefined') print(out);
        })();
        """
        )

        node_bin = shutil.which("node")
        jsc_bin = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc"
        cmd = [node_bin, "-e", eval_harness] if node_bin else [jsc_bin, "-e", eval_harness]
        proc = subprocess.run(cmd, cwd=root_dir, capture_output=True, text=True, check=True)
        result = json.loads(proc.stdout.strip().splitlines()[-1])

        self.assertEqual(result["firstModel"], "gemini-4.3-flash")
        self.assertFalse(result["hasNonTextOrLegacy"])
        self.assertLess(
            result["ranked"].index("gemini-4.3-flash"),
            result["ranked"].index("gemini-3.2-flash-lite"),
        )
        self.assertLess(
            result["ranked"].index("gemini-3.2-flash-lite"),
            result["ranked"].index("gemini-4-argon"),
        )
        self.assertEqual(result["modelUsed"], "gemini-3.2-flash-lite")
        self.assertEqual(result["retryAttempts"], 2)
        self.assertEqual(result["retryModelUsed"], "gemini-3.8-flash")
        self.assertFalse(result["plugUs"])
        self.assertFalse(result["plugIn"])
        self.assertTrue(result["plugGb"])
        self.assertTrue(result["plugJp"])

    def test_eval_db_crud_and_custom_rules_persistence(self):
        """Eval: Verifies SQLite persistence for profiles, custom template items, trips, and item checkoff statistics."""
        profile = self.db.add_template_item(
            module_or_section="work",
            item_name="Portable Monitor",
            category="Electronics",
            is_doorway_critical=True,
            is_high_loss_return=True,
        )
        work_items = [i["name"] for i in profile["activity_modules"]["work"]["items"]]
        self.assertIn("Portable Monitor", work_items)

        weather = fetch_destination_weather("Seattle", duration_days=4, home_country_code="US")
        items = build_complete_checklist(
            trip_id=1,
            weather=weather,
            profile=profile,
            selected_tags=["work", "rental_car"],
        )
        created = self.db.create_trip(weather=weather, tags=["work", "rental_car"], items=items)
        self.assertEqual(created["destination"], "Seattle")
        self.assertGreater(created["stats"]["total_items"], 0)
        self.assertEqual(created["stats"]["packed_outbound"], 0)

    def test_eval_webapp_back_navigation_and_responsive_ui(self):
        """Eval: Verifies WebAppView.js back-button navigation hierarchy (modals -> drawer -> interview steps -> home) and responsive UI classes."""
        root_dir = os.path.dirname(os.path.dirname(__file__))
        webapp_path = os.path.join(root_dir, "cloudflare", "src", "ui", "WebAppView.js")
        with open(webapp_path, "r", encoding="utf-8") as f:
            webapp_src = f.read()

        # 1. Verify responsive layout classes exist on header, date pickers, trip cards, and Gemini suggestions
        self.assertIn("grid-cols-1 sm:grid-cols-2", webapp_src)
        self.assertIn("flex-wrap", webapp_src)
        self.assertIn("min-w-0", webapp_src)

        # 2. Verify back navigation methods and popstate listener are wired in PackTrackClient
        self.assertIn('window.addEventListener("popstate"', webapp_src)
        self.assertIn("handleBackAction(", webapp_src)
        self.assertIn("stepBackInterview()", webapp_src)
        self.assertIn("closeActiveModals()", webapp_src)
        self.assertIn("isDrawerOpen()", webapp_src)
        self.assertIn("pushHistoryState(", webapp_src)
        self.assertIn("formatDateRange(", webapp_src)

        # 3. Verify stepBackInterview state transitions and formatDateRange in JS runtime
        import json
        import shutil
        import subprocess

        start_idx = webapp_src.index("stepBackInterview() {")
        end_idx = webapp_src.index("handleBackAction(fromPopState = false)")
        step_back_method = webapp_src[start_idx:end_idx]

        fmt_start = webapp_src.index("formatDateRange(rawStartDate, durationDays, rawEndDate) {")
        fmt_end = webapp_src.index("isPlugInfoNeeded(country, countryCode, plugTypes) {")
        fmt_method = webapp_src[fmt_start:fmt_end].replace("\\`", "`").replace("\\$", "$")

        plug_start = webapp_src.index("isPlugInfoNeeded(country, countryCode, plugTypes) {")
        plug_end = webapp_src.index("setAccentHue(hueVal) {")
        plug_method = webapp_src[plug_start:plug_end].replace("\\`", "`").replace("\\$", "$")

        js_harness = f"""
        const app = {{
          currentScreen: 'interview',
          iv: {{ step: 'WORK_PURPOSE', carMode: 'rental_car' }},
          renderCalls: 0,
          render() {{ this.renderCalls++; }},
          navigateHome() {{ this.currentScreen = 'home'; }},
          {step_back_method},
          {fmt_method},
          {plug_method}
        }};
        const stepsVisited = [app.iv.step];
        while (app.currentScreen === 'interview') {{
          app.stepBackInterview();
          stepsVisited.push(app.currentScreen === 'home' ? 'HOME' : app.iv.step);
        }}

        const ownCarApp = {{
          currentScreen: 'interview',
          iv: {{ step: 'WORK_PURPOSE', carMode: 'own_car' }},
          render() {{}},
          navigateHome() {{ this.currentScreen = 'home'; }},
          {step_back_method}
        }};
        ownCarApp.stepBackInterview();
        const ownCarPrevStep = ownCarApp.iv.step;

        const sameMonthRange = app.formatDateRange('2026-11-10', 4);
        const crossMonthRange = app.formatDateRange('2026-11-28', 5);
        const crossYearRange = app.formatDateRange('2026-12-29', 5, '2027-01-03');
        const plugUs = app.isPlugInfoNeeded('United States', 'US', 'Type A/B');
        const plugIn = app.isPlugInfoNeeded('India', 'IN', 'Type C/D/M');
        const plugGb = app.isPlugInfoNeeded('United Kingdom', 'GB', 'Type G');

        const out = JSON.stringify({{ stepsVisited, ownCarPrevStep, sameMonthRange, crossMonthRange, crossYearRange, plugUs, plugIn, plugGb }});
        if (typeof console !== 'undefined' && console.log) console.log(out);
        else if (typeof print !== 'undefined') print(out);
        """
        node_bin = shutil.which("node")
        jsc_bin = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc"
        cmd = [node_bin, "-e", js_harness] if node_bin else [jsc_bin, "-e", js_harness]
        proc = subprocess.run(cmd, cwd=root_dir, capture_output=True, text=True, check=True)
        res = json.loads(proc.stdout.strip().splitlines()[-1])

        self.assertEqual(
            res["stepsVisited"],
            ["WORK_PURPOSE", "BAGS", "RENTAL_CAR", "FLIGHT_OR_CAR", "EXACT_DATES", "DESTINATION", "HOME"],
        )
        self.assertEqual(res["ownCarPrevStep"], "FLIGHT_OR_CAR")
        self.assertEqual(res["sameMonthRange"], "Nov 10–14")
        self.assertEqual(res["crossMonthRange"], "Nov 28 – Dec 3")
        self.assertEqual(res["crossYearRange"], "Dec 29, 2026 – Jan 3, 2027")
        self.assertFalse(res["plugUs"])
        self.assertFalse(res["plugIn"])
        self.assertTrue(res["plugGb"])

    def test_eval_android_best_practices_and_back_navigation_guards(self):
        """Eval: Guards AndroidManifest.xml and Compose UI screens against regressions in back navigation, state saving, and responsive layout."""
        root_dir = os.path.dirname(os.path.dirname(__file__))
        manifest_path = os.path.join(root_dir, "android", "app", "src", "main", "AndroidManifest.xml")
        ui_dir = os.path.join(root_dir, "android", "app", "src", "main", "java", "com", "packtrack", "app", "ui")

        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = f.read()
        self.assertIn('android:windowSoftInputMode="adjustResize"', manifest)
        self.assertIn('android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"', manifest)

        with open(os.path.join(ui_dir, "ChecklistScreen.kt"), "r", encoding="utf-8") as f:
            checklist_kt = f.read()
        self.assertIn("rememberSaveable", checklist_kt)
        self.assertIn("backStack", checklist_kt)
        self.assertIn("BackHandler(enabled = currentScreen != AppScreen.HOME)", checklist_kt)

        with open(os.path.join(ui_dir, "HomeScreen.kt"), "r", encoding="utf-8") as f:
            home_kt = f.read()
        self.assertIn("BackHandler(enabled = drawerState.isOpen)", home_kt)
        self.assertIn("FlowRow(", home_kt)
        self.assertIn("formatDateRange(", home_kt)

        with open(os.path.join(ui_dir, "GeminiInterviewScreen.kt"), "r", encoding="utf-8") as f:
            interview_kt = f.read()
        self.assertIn("fun handleStepBack()", interview_kt)
        self.assertIn("BackHandler(enabled = !isBusy)", interview_kt)
        self.assertIn("formatDateRange(", interview_kt)

        with open(os.path.join(ui_dir, "TripChecklistScreen.kt"), "r", encoding="utf-8") as f:
            trip_kt = f.read()
        self.assertIn("FlowRow(", trip_kt)
        self.assertIn("formatDateRange(", trip_kt)
        self.assertIn("parseTripTags(", trip_kt)
        self.assertIn("newCarMode = chosenCarMode", trip_kt)

        # Verify Recreate Trip modal pre-populates current details and sends overrides in WebAppView.js & HttpRouter.js
        webapp_path = os.path.join(root_dir, "cloudflare", "src", "ui", "WebAppView.js")
        with open(webapp_path, "r", encoding="utf-8") as f:
            webapp_js = f.read()
        self.assertIn('id="rt-start-date"', webapp_js)
        self.assertIn('id="rt-end-date"', webapp_js)
        self.assertIn('id="rt-car"', webapp_js)
        self.assertIn('id="rt-purpose"', webapp_js)
        self.assertIn('id="rt-luggage"', webapp_js)
        self.assertIn("onRecreateDateChange()", webapp_js)
        self.assertIn("onRecreateCarChange()", webapp_js)
        self.assertIn('id="edit-item-modal"', webapp_js)
        self.assertIn("openEditItemModal(", webapp_js)
        self.assertIn("submitEditItem()", webapp_js)
        self.assertIn("trip.tripMode === \"DOORWAY\"", webapp_js)

        # Verify 10-year (until explicit Sign Out) login session persistence in HttpRouter.js, WebAppView.js, and ChecklistScreen.kt
        router_path = os.path.join(root_dir, "cloudflare", "src", "controllers", "HttpRouter.js")
        with open(router_path, "r", encoding="utf-8") as f:
            router_js = f.read()
        self.assertIn("SESSION_MAX_AGE_MS = 3650 * 24 * 60 * 60 * 1000", router_js)
        self.assertIn("mintWorkerSessionToken", router_js)
        self.assertIn("verifyWorkerSessionToken", router_js)
        self.assertIn("verifyGoogleJwtWithJwks", router_js)
        self.assertIn("Array.isArray(body.tags)", router_js)
        self.assertIn("body.existingItems", router_js)
        self.assertIn("active_user_id", checklist_kt)
        self.assertIn("GoogleSignIn", checklist_kt)
        self.assertIn(".getLastSignedInAccount", checklist_kt)

        # Verify QA Subagent 4 & 5 audit fixes across WebAppView.js, PackingEngineService.js, McpRouter.js, and Android UI
        self.assertIn('<option value="0.35">0.35 per day (1 per 3 days)</option>', webapp_js)
        self.assertIn("getLocalTodayIso()", webapp_js)
        self.assertIn("Number.isFinite(parsedL)", webapp_js)
        self.assertIn("Number.isFinite(parsed) && String(hueVal).trim() !== \"\" ? parsed : 160", webapp_js)
        self.assertIn("else if (this.activeTripIdx !== null && idx < this.activeTripIdx)", webapp_js)

        engine_path = os.path.join(root_dir, "cloudflare", "src", "services", "PackingEngineService.js")
        with open(engine_path, "r", encoding="utf-8") as f:
            engine_js = f.read()
        self.assertIn('countryCode: weather.countryCode || ""', engine_js)

        mcp_path = os.path.join(root_dir, "cloudflare", "src", "mcp", "McpRouter.js")
        with open(mcp_path, "r", encoding="utf-8") as f:
            mcp_js = f.read()
        self.assertIn('geminiApiKey: this.env?.GEMINI_API_KEY || ""', mcp_js)

        self.assertIn("BackHandler(enabled = isBusy)", interview_kt)
        self.assertIn("quantity = editQty.toIntOrNull()?.coerceAtLeast(1) ?: item.quantity", trip_kt)


if __name__ == "__main__":
    unittest.main()
