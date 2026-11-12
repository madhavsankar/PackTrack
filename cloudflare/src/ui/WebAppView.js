/**
 * Minimalist Responsive Web App View for PackTrack.
 *
 * Navigation Architecture:
 *  - Top Bar with Hamburger Menu (`☰`) housing Settings (Profile, Default Template, Theme Color, Cloud Sync)
 *  - Home Screen: Clean list of Trip Cards + `+ New Trip` button
 *  - Trip Details Screen: Opens when clicking a trip card (3-mode switcher, progress bar, categorized items)
 *  - Default Template Screen: Opens from the Hamburger menu
 */

export const WEB_APP_HTML = `<!DOCTYPE html>
<html lang="en" class="dark">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>PackTrack</title>
  <link rel="manifest" href="/manifest.json" />
  <script src="https://cdn.tailwindcss.com"></script>
  <script src="https://accounts.google.com/gsi/client" async defer></script>
  <style>
    :root {
      --accent: #10b981;
      --accent-soft: rgba(16, 185, 129, 0.14);
      --accent-border: rgba(16, 185, 129, 0.38);
    }
    body {
      background: #0b0f17;
      color: #f1f5f9;
      font-family: -apple-system, BlinkMacSystemFont, "Inter", "Segoe UI", Roboto, sans-serif;
      -webkit-tap-highlight-color: transparent;
    }
    .surface-card {
      background: #131a26;
      border: 1px solid #232f42;
    }
    .surface-subtle {
      background: #1c2536;
      border: 1px solid #232f42;
    }
    .accent-bg {
      background-color: var(--accent) !important;
      color: #06130e !important;
    }
    .accent-text {
      color: var(--accent) !important;
    }
    .accent-pill {
      background-color: var(--accent-soft) !important;
      border-color: var(--accent-border) !important;
      color: var(--accent) !important;
    }
    .item-checked {
      opacity: 0.5;
    }
  </style>
</head>
<body class="min-h-screen pb-24">
  <div class="max-w-2xl mx-auto px-4 pt-4 space-y-5">
    <!-- Minimalist Top App Bar -->
    <header class="flex items-center justify-between gap-3 py-1">
      <div class="flex items-center gap-3 min-w-0">
        <!-- Hamburger Button (Home) or Back Button (Detail / Template) -->
        <button
          type="button"
          id="nav-icon-btn"
          onclick="app.handleNavButtonClick()"
          class="surface-subtle w-9 h-9 rounded-xl flex items-center justify-center text-slate-200 hover:text-white transition shrink-0"
          aria-label="Menu"
        >
          <span id="nav-icon-symbol" class="text-base leading-none">☰</span>
        </button>
        <div class="min-w-0">
          <h1 id="header-title" class="text-base font-bold tracking-tight text-white truncate">PackTrack</h1>
          <p id="header-subtitle" class="text-xs text-slate-400 truncate">Your Trips</p>
        </div>
      </div>

      <!-- Context Action on Right -->
      <div id="header-actions" class="flex items-center gap-2 shrink-0"></div>
    </header>

    <!-- ========================================================= -->
    <!-- SCREEN 1: HOME (List of Trips Only)                       -->
    <!-- ========================================================= -->
    <section id="screen-home" class="space-y-3">
      <div id="trips-list" class="space-y-3"></div>

      <!-- Empty Trips State -->
      <div id="empty-trips" class="hidden surface-card rounded-2xl p-10 text-center space-y-3">
        <p class="text-sm font-semibold text-white">No trips yet</p>
        <p class="text-xs text-slate-400 max-w-xs mx-auto">
          Create a new trip to generate a weather-aware packing checklist from your default template.
        </p>
        <button type="button" onclick="app.openNewTripModal()" class="accent-bg px-4 py-2.5 rounded-xl text-xs font-semibold">
          + New Trip
        </button>
      </div>
    </section>

    <!-- ========================================================= -->
    <!-- SCREEN 2: TRIP DETAILS (Checklist for Selected Trip)      -->
    <!-- ========================================================= -->
    <section id="screen-trip-detail" class="hidden space-y-4">
      <!-- Trip Summary & Mode Switcher Card -->
      <div class="surface-card rounded-2xl p-4 space-y-3.5">
        <!-- Segmented 3-Mode Pill Switcher (Pack | Doorway | Checkout) -->
        <div class="grid grid-cols-3 gap-1 bg-[#0b0f17] p-1 rounded-xl border border-[#232f42]">
          <button type="button" id="mode-OUTBOUND" onclick="app.setTripMode('OUTBOUND')" class="py-1.5 rounded-lg text-xs font-semibold transition">
            Pack
          </button>
          <button type="button" id="mode-DOORWAY" onclick="app.setTripMode('DOORWAY')" class="py-1.5 rounded-lg text-xs font-semibold transition">
            Doorway
          </button>
          <button type="button" id="mode-RETURN_SWEEP" onclick="app.setTripMode('RETURN_SWEEP')" class="py-1.5 rounded-lg text-xs font-semibold transition">
            Checkout
          </button>
        </div>

        <!-- Progress Bar & Recreate Action -->
        <div class="space-y-1.5">
          <div class="flex flex-wrap items-center justify-between text-xs gap-2">
            <span id="progress-label" class="font-medium text-slate-300">Outbound Progress</span>
            <div class="flex items-center gap-2 shrink-0">
              <button
                type="button"
                onclick="app.openRecreateTripModal()"
                class="surface-subtle px-2.5 py-1 rounded-lg text-[11px] font-semibold accent-text hover:opacity-90 transition"
              >
                ↻ Recreate
              </button>
              <span id="progress-count" class="font-bold accent-text">0 / 0</span>
            </div>
          </div>
          <div class="w-full h-1.5 bg-[#1c2536] rounded-full overflow-hidden">
            <div id="progress-bar" class="h-full accent-bg transition-all duration-200" style="width: 0%"></div>
          </div>
        </div>
      </div>

      <!-- Gemini Unavailable / Rule-Based Fallback Notice Banner -->
      <div
        id="gemini-fallback-banner"
        class="hidden rounded-xl border border-amber-500/45 bg-amber-500/15 px-3.5 py-2.5 text-xs text-amber-300"
      >
        ⚠️ Gemini is unavailable — rule-based backup results were used for generating/re-generating this checklist.
      </div>

      <!-- Category-Grouped Checklist -->
      <div id="checklist-groups" class="space-y-4"></div>
    </section>

    <!-- ========================================================= -->
    <!-- SCREEN 3: DEFAULT TEMPLATE EDITOR (Opened from Menu)      -->
    <!-- ========================================================= -->
    <section id="screen-template" class="hidden space-y-4">
      <div class="surface-card rounded-2xl p-4 space-y-3">
        <div class="flex items-center justify-between gap-3">
          <div>
            <h2 class="text-sm font-bold text-white">Default Packing Items</h2>
            <p class="text-xs text-slate-400">Tap any item to edit its condition, category, or bag</p>
          </div>
          <button type="button" onclick="app.openAddRuleModal()" class="accent-bg px-3.5 py-2 rounded-xl text-xs font-semibold shrink-0">
            + Add Item
          </button>
        </div>
        <div class="grid grid-cols-2 gap-1 bg-[#0b0f17] p-1 rounded-xl border border-[#232f42]">
          <button type="button" id="tpl-group-cond" onclick="app.setTemplateGrouping('condition')" class="py-1.5 rounded-lg text-xs font-semibold accent-bg transition">
            By Condition
          </button>
          <button type="button" id="tpl-group-cat" onclick="app.setTemplateGrouping('category')" class="py-1.5 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition">
            By Category
          </button>
        </div>
      </div>
      <div id="template-groups" class="space-y-4"></div>
    </section>

    <!-- ========================================================= -->
    <!-- SCREEN 4: INTERACTIVE GEMINI TRIP INTERVIEW               -->
    <!-- ========================================================= -->
    <section id="screen-interview" class="hidden space-y-5">
      <!-- 6-Step Progress Bar -->
      <div id="iv-stepper" class="grid grid-cols-6 gap-1.5 pt-1"></div>

      <!-- Question Header -->
      <div class="space-y-1.5">
        <h2 id="iv-title" class="text-lg font-bold text-white">Where are you heading?</h2>
        <p id="iv-subtitle" class="text-xs text-slate-400">Type your destination city below, or tap a city preset.</p>
      </div>

      <!-- Dynamic Step Content -->
      <div id="iv-body" class="space-y-3"></div>
    </section>
  </div>

  <!-- Floating Action Button (+ New Trip on Home, + Item on Trip Detail) -->
  <button
    type="button"
    id="fab-btn"
    onclick="app.handleFabClick()"
    class="fixed bottom-6 right-6 accent-bg px-4 py-3 rounded-2xl shadow-lg text-xs font-bold flex items-center gap-1.5 z-20 hover:opacity-95 transition"
  >
    <span class="text-sm leading-none">+</span>
    <span id="fab-label">New Trip</span>
  </button>

  <!-- ========================================================= -->
  <!-- HAMBURGER SETTINGS DRAWER                                 -->
  <!-- ========================================================= -->
  <div id="drawer-backdrop" onclick="app.closeDrawer()" class="hidden fixed inset-0 bg-black/65 backdrop-blur-sm z-40"></div>
  <aside
    id="settings-drawer"
    class="fixed top-0 left-0 bottom-0 w-72 bg-[#131a26] border-r border-[#232f42] z-50 p-5 flex flex-col justify-between transform -translate-x-full transition-transform duration-200"
  >
    <div class="space-y-6">
      <!-- Drawer Header -->
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-sm font-bold text-white">Settings & Menu</h2>
          <p id="drawer-sync-status" class="text-[11px] text-slate-400">Cloud Sync Active</p>
        </div>
        <button type="button" onclick="app.closeDrawer()" class="text-slate-400 hover:text-white text-sm px-2 py-1">✕</button>
      </div>

      <!-- Cloud Account Section -->
      <div class="space-y-2">
        <label class="text-[11px] font-semibold uppercase tracking-wider text-slate-400">🔐 Google Account</label>
        <div class="surface-subtle rounded-xl p-3 space-y-2.5">
          <div class="flex items-center gap-2">
            <span id="drawer-user-dot" class="w-2 h-2 rounded-full accent-bg shrink-0"></span>
            <span id="drawer-user-label" class="text-xs font-medium text-white truncate">Signed In</span>
          </div>
          <div class="flex gap-1.5">
            <input
              id="drawer-email-input"
              type="text"
              readonly
              placeholder="Not signed in"
              class="w-full bg-[#0b0f17] text-slate-300 border border-[#232f42] rounded-lg px-2.5 py-1.5 text-xs focus:outline-none"
            />
            <button type="button" onclick="app.signOutGoogle()" class="bg-rose-500/20 text-rose-300 border border-rose-500/40 px-3 py-1.5 rounded-lg text-xs font-semibold shrink-0 hover:bg-rose-500/30">
              Sign Out
            </button>
          </div>
        </div>
      </div>

      <!-- Navigation Links -->
      <div class="space-y-1.5">
        <label class="text-[11px] font-semibold uppercase tracking-wider text-slate-400">Manage</label>
        <button
          type="button"
          onclick="app.openHomeFromDrawer()"
          class="w-full text-left px-3 py-2.5 rounded-xl text-xs font-medium text-slate-200 hover:bg-[#1c2536] flex items-center justify-between transition"
        >
          <span>All Trips</span>
          <span id="drawer-trips-count" class="text-slate-400">0</span>
        </button>
        <button
          type="button"
          onclick="app.openTemplateFromDrawer()"
          class="w-full text-left px-3 py-2.5 rounded-xl text-xs font-medium text-slate-200 hover:bg-[#1c2536] flex items-center justify-between transition"
        >
          <span>Default Packing Template</span>
          <span id="drawer-template-count" class="text-slate-400">0</span>
        </button>
      </div>

      <!-- Temperature Unit Preference -->
      <div class="space-y-2">
        <label class="text-[11px] font-semibold uppercase tracking-wider text-slate-400">Temperature Unit</label>
        <div class="grid grid-cols-2 gap-1 bg-[#0b0f17] p-1 rounded-xl border border-[#232f42]">
          <button type="button" id="temp-unit-f" onclick="app.setTempUnit('F')" class="py-1.5 rounded-lg text-xs font-semibold accent-bg transition">
            °F (Fahrenheit)
          </button>
          <button type="button" id="temp-unit-c" onclick="app.setTempUnit('C')" class="py-1.5 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition">
            °C (Celsius)
          </button>
        </div>
      </div>

      <!-- Continuous Rainbow Spectrum Accent Color Chooser -->
      <div class="space-y-2">
        <div class="flex items-center justify-between">
          <label class="text-[11px] font-semibold uppercase tracking-wider text-slate-400">Accent Color</label>
          <span id="accent-preview-dot" class="w-4 h-4 rounded-full accent-bg border border-white/80 shrink-0"></span>
        </div>
        <input
          id="accent-hue-slider"
          type="range"
          min="0"
          max="360"
          value="160"
          oninput="app.setAccentHue(this.value)"
          class="w-full h-3 rounded-lg appearance-none cursor-pointer"
          style="background: linear-gradient(to right, hsl(0,82%,50%), hsl(60,82%,50%), hsl(120,82%,50%), hsl(180,82%,50%), hsl(240,82%,50%), hsl(300,82%,50%), hsl(360,82%,50%));"
        />
      </div>
    </div>

    <!-- Bottom Sync Button inside Drawer -->
    <button
      type="button"
      onclick="app.loadState(true)"
      class="surface-subtle w-full py-2.5 rounded-xl text-xs font-medium text-slate-200 hover:text-white flex items-center justify-center gap-2 transition"
    >
      <span>↻</span>
      <span>Sync with Cloud Now</span>
    </button>
  </aside>

  <!-- ========================================================= -->
  <!-- MODAL: NEW TRIP SETUP                                     -->
  <!-- ========================================================= -->
  <div id="new-trip-modal" class="hidden fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="surface-card rounded-2xl p-5 w-full max-w-md space-y-4">
      <div class="flex items-center justify-between">
        <h3 class="text-sm font-bold text-white">New Trip</h3>
        <button type="button" onclick="app.closeModals()" class="text-slate-400 hover:text-white text-xs">✕</button>
      </div>
      <div class="space-y-3">
        <div>
          <label class="text-[11px] font-semibold text-slate-400 uppercase">Destination City</label>
          <input id="nt-dest" type="text" placeholder="e.g. Seattle, London, Tokyo" value="Seattle" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
        </div>
        <div class="grid grid-cols-2 gap-2">
          <div>
            <label class="text-[11px] font-semibold text-slate-400 uppercase">Start Date</label>
            <input id="nt-start-date" type="date" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
          </div>
          <div>
            <label class="text-[11px] font-semibold text-slate-400 uppercase">Duration (Days)</label>
            <input id="nt-days" type="number" min="1" max="30" value="5" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
          </div>
        </div>
        <div class="grid grid-cols-2 gap-2">
          <div>
            <label class="text-[11px] font-semibold text-slate-400 uppercase">Trip Type</label>
            <select id="nt-purpose" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
              <option value="work">Work / Business</option>
              <option value="leisure">Leisure / Personal</option>
            </select>
          </div>
          <div>
            <label class="text-[11px] font-semibold text-slate-400 uppercase">Vehicle</label>
            <select id="nt-car" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
              <option value="rental_car">Flight + Rental Car</option>
              <option value="own_car">Driving Own Vehicle</option>
              <option value="no_car">Flight / Transit Only</option>
            </select>
          </div>
        </div>
        <div>
          <label class="text-[11px] font-semibold text-slate-400 uppercase">Luggage Space</label>
          <select id="nt-luggage" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
            <option value="carry_on">Carry-On + Personal Item</option>
            <option value="checked_bag">Checked Suitcase + Carry-On</option>
            <option value="personal_only">Backpack / Personal Item Only</option>
          </select>
        </div>
      </div>
      <div class="flex justify-end gap-2 pt-1">
        <button type="button" onclick="app.closeModals()" class="surface-subtle px-3.5 py-2 rounded-xl text-xs text-slate-300">Cancel</button>
        <button type="button" id="nt-submit-btn" onclick="app.submitNewTrip()" class="accent-bg px-4 py-2 rounded-xl text-xs font-semibold">Create Trip</button>
      </div>
    </div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL: ADD ITEM TO TRIP                                   -->
  <!-- ========================================================= -->
  <div id="add-item-modal" class="hidden fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="surface-card rounded-2xl p-5 w-full max-w-sm space-y-3">
      <h3 class="text-sm font-bold text-white">Add Item to Trip</h3>
      <input id="ai-name" type="text" placeholder="Item name" class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
      <div class="grid grid-cols-2 gap-2">
        <input id="ai-qty" type="number" min="1" value="1" placeholder="Qty" class="bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
        <select id="ai-bag" class="bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
          <option value="Personal">Personal</option>
          <option value="Carry-On" selected>Carry-On</option>
          <option value="Check-In">Check-In</option>
        </select>
      </div>
      <div class="space-y-1.5">
        <div class="flex items-center justify-between">
          <label class="text-[11px] font-semibold text-slate-400 uppercase">Category</label>
          <button type="button" onclick="app.toggleNewCategoryBox('ai')" class="text-[11px] font-semibold accent-text hover:underline">+ Add Category</button>
        </div>
        <select id="ai-cat" onchange="app.handleCategorySelectChange('ai')" class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
          <option value="On Me">On Me</option>
          <option value="Docs">Docs</option>
          <option value="Electronics">Electronics</option>
          <option value="Clothes">Clothes</option>
          <option value="Toiletry">Toiletry</option>
          <option value="Others">Others</option>
          <option value="__new__">+ Add Category...</option>
        </select>
        <div id="ai-new-cat-box" class="hidden flex gap-1.5 pt-1">
          <input id="ai-new-cat-input" type="text" placeholder="New category name" class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-1.5 text-xs" />
          <button type="button" onclick="app.saveNewCategory('ai')" class="accent-bg px-3 py-1.5 rounded-xl text-xs font-semibold shrink-0">Add</button>
        </div>
      </div>
      <label class="flex items-center gap-2 text-xs text-slate-300">
        <input id="ai-doorway" type="checkbox" class="rounded" />
        <span>Include in 60-Second Doorway Check</span>
      </label>
      <label class="flex items-center gap-2 text-xs text-slate-300">
        <input id="ai-save-template" type="checkbox" class="rounded" />
        <span>Also save to my Default Template</span>
      </label>
      <div class="flex justify-end gap-2 pt-2">
        <button type="button" onclick="app.closeModals()" class="surface-subtle px-3.5 py-2 rounded-xl text-xs text-slate-300">Cancel</button>
        <button type="button" onclick="app.submitAddItem()" class="accent-bg px-4 py-2 rounded-xl text-xs font-semibold">Add Item</button>
      </div>
    </div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL: EDIT TRIP ITEM (Opened via Long-Press)             -->
  <!-- ========================================================= -->
  <div id="edit-item-modal" class="hidden fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="surface-card rounded-2xl p-5 w-full max-w-sm space-y-3">
      <div class="flex items-center justify-between">
        <h3 class="text-sm font-bold text-white">Edit Checklist Item</h3>
        <button type="button" onclick="app.closeModals()" class="text-slate-400 hover:text-white text-xs">✕</button>
      </div>
      <div>
        <label class="text-[11px] font-semibold text-slate-400 uppercase">Item Name</label>
        <input id="ei-name" type="text" placeholder="Item name" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
      </div>
      <div class="grid grid-cols-2 gap-2">
        <div>
          <label class="text-[11px] font-semibold text-slate-400 uppercase">Quantity</label>
          <input id="ei-qty" type="number" min="1" value="1" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
        </div>
        <div>
          <label class="text-[11px] font-semibold text-slate-400 uppercase">Bag Location</label>
          <select id="ei-bag" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
            <option value="Personal">Personal</option>
            <option value="Carry-On">Carry-On</option>
            <option value="Check-In">Check-In</option>
          </select>
        </div>
      </div>
      <div class="space-y-1.5">
        <div class="flex items-center justify-between">
          <label class="text-[11px] font-semibold text-slate-400 uppercase">Category</label>
          <button type="button" onclick="app.toggleNewCategoryBox('ei')" class="text-[11px] font-semibold accent-text hover:underline">+ Add Category</button>
        </div>
        <select id="ei-cat" onchange="app.handleCategorySelectChange('ei')" class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
          <option value="On Me">On Me</option>
          <option value="Docs">Docs</option>
          <option value="Electronics">Electronics</option>
          <option value="Clothes">Clothes</option>
          <option value="Toiletry">Toiletry</option>
          <option value="Others">Others</option>
          <option value="__new__">+ Add Category...</option>
        </select>
        <div id="ei-new-cat-box" class="hidden flex gap-1.5 pt-1">
          <input id="ei-new-cat-input" type="text" placeholder="New category name" class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-1.5 text-xs" />
          <button type="button" onclick="app.saveNewCategory('ei')" class="accent-bg px-3 py-1.5 rounded-xl text-xs font-semibold shrink-0">Add</button>
        </div>
      </div>
      <label class="flex items-center gap-2 text-xs text-slate-300 pt-1">
        <input id="ei-doorway" type="checkbox" class="rounded" />
        <span>Include in 60-Second Doorway Check</span>
      </label>
      <button
        type="button"
        id="ei-promote-btn"
        onclick="app.promoteEditedItemToTemplate()"
        class="w-full surface-subtle py-2 px-3 rounded-xl text-xs font-medium accent-text hover:opacity-90 transition text-left flex items-center justify-between"
      >
        <span>★ Save to my Default Packing Template</span>
        <span class="text-[10px] text-slate-400">Always</span>
      </button>
      <div class="flex items-center justify-between gap-2 pt-2">
        <button
          type="button"
          onclick="app.deleteEditedItem()"
          class="bg-rose-500/15 text-rose-300 border border-rose-500/35 px-3 py-2 rounded-xl text-xs font-semibold hover:bg-rose-500/25 transition"
        >
          Remove
        </button>
        <div class="flex gap-2">
          <button type="button" onclick="app.closeModals()" class="surface-subtle px-3.5 py-2 rounded-xl text-xs text-slate-300">Cancel</button>
          <button type="button" onclick="app.submitEditItem()" class="accent-bg px-4 py-2 rounded-xl text-xs font-semibold">Save</button>
        </div>
      </div>
    </div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL: ADD / EDIT ITEM IN DEFAULT TEMPLATE                -->
  <!-- ========================================================= -->
  <div id="add-rule-modal" class="hidden fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="surface-card rounded-2xl p-5 w-full max-w-sm space-y-3">
      <h3 id="ar-modal-title" class="text-sm font-bold text-white">Add Item</h3>
      <input id="ar-name" type="text" placeholder="Item name" class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
      <div class="grid grid-cols-2 gap-2">
        <div>
          <div class="flex items-center justify-between">
            <label class="text-[11px] font-semibold text-slate-400 uppercase">Category</label>
            <button type="button" onclick="app.toggleNewCategoryBox('ar')" class="text-[11px] font-semibold accent-text hover:underline">+ Add</button>
          </div>
          <select id="ar-cat" onchange="app.handleCategorySelectChange('ar')" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
            <option value="On Me">On Me</option>
            <option value="Docs">Docs</option>
            <option value="Electronics">Electronics</option>
            <option value="Clothes">Clothes</option>
            <option value="Toiletry">Toiletry</option>
            <option value="Others">Others</option>
            <option value="__new__">+ Add Category...</option>
          </select>
        </div>
        <div>
          <label class="text-[11px] font-semibold text-slate-400 uppercase">Bag Type</label>
          <select id="ar-bag" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
            <option value="Personal">Personal</option>
            <option value="Carry-On" selected>Carry-On</option>
            <option value="Check-In">Check-In</option>
          </select>
        </div>
      </div>
      <div id="ar-new-cat-box" class="hidden flex gap-1.5">
        <input id="ar-new-cat-input" type="text" placeholder="New category name" class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-1.5 text-xs" />
        <button type="button" onclick="app.saveNewCategory('ar')" class="accent-bg px-3 py-1.5 rounded-xl text-xs font-semibold shrink-0">Add</button>
      </div>
      <div>
        <label class="text-[11px] font-semibold text-slate-400 uppercase">Condition (leave blank for Always)</label>
        <input id="ar-mod" type="text" placeholder="e.g. Only when taking rental car" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs" />
      </div>
      <div>
        <label class="text-[11px] font-semibold text-slate-400 uppercase">Scale per day</label>
        <select id="ar-scale-select" class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs">
          <option value="fixed">Fixed (1 total for trip)</option>
          <option value="0.35">0.35 per day (1 per 3 days)</option>
          <option value="0.5">0.5 per day</option>
          <option value="1">1 per day</option>
          <option value="2">2 per day</option>
          <option value="3">3 per day</option>
        </select>
      </div>
      <div class="flex justify-end gap-2 pt-2">
        <button type="button" onclick="app.closeModals()" class="surface-subtle px-3.5 py-2 rounded-xl text-xs text-slate-300">Cancel</button>
        <button type="button" onclick="app.submitAddRule()" class="accent-bg px-4 py-2 rounded-xl text-xs font-semibold">Save Item</button>
      </div>
    </div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL: CONFIRM DELETE TRIP                                -->
  <!-- ========================================================= -->
  <div id="delete-trip-modal" class="hidden fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="surface-card rounded-2xl p-5 w-full max-w-sm space-y-3">
      <h3 id="dt-title" class="text-sm font-bold text-white">Delete Trip?</h3>
      <p id="dt-desc" class="text-xs text-slate-300">Are you sure you want to remove this trip checklist?</p>
      <div class="flex justify-end gap-2 pt-2">
        <button type="button" onclick="app.closeModals()" class="surface-subtle px-3.5 py-2 rounded-xl text-xs text-slate-300">Cancel</button>
        <button type="button" onclick="app.confirmDeleteTrip()" class="bg-rose-500 hover:bg-rose-600 text-white px-4 py-2 rounded-xl text-xs font-semibold transition">Delete</button>
      </div>
    </div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL: CONFIRM DELETE TEMPLATE ITEM                       -->
  <!-- ========================================================= -->
  <div id="delete-rule-modal" class="hidden fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="surface-card rounded-2xl p-5 w-full max-w-sm space-y-3">
      <h3 id="dr-title" class="text-sm font-bold text-white">Delete Item?</h3>
      <p id="dr-desc" class="text-xs text-slate-300">Are you sure you want to remove this item from your Default Template?</p>
      <div class="flex justify-end gap-2 pt-2">
        <button type="button" onclick="app.closeModals()" class="surface-subtle px-3.5 py-2 rounded-xl text-xs text-slate-300">Cancel</button>
        <button type="button" onclick="app.confirmDeleteRule()" class="bg-rose-500 hover:bg-rose-600 text-white px-4 py-2 rounded-xl text-xs font-semibold transition">Delete</button>
      </div>
    </div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL: CONFIRM RECREATE TRIP CHECKLIST                    -->
  <!-- ========================================================= -->
  <div id="recreate-trip-modal" class="hidden fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="surface-card rounded-2xl p-5 w-full max-w-md space-y-3.5">
      <div class="flex items-center justify-between">
        <h3 id="rt-modal-title" class="text-sm font-bold text-white">Recreate Checklist?</h3>
        <button type="button" onclick="app.closeModals()" class="text-slate-400 hover:text-white text-xs">✕</button>
      </div>
      <p class="text-xs text-slate-300">
        Review or edit any trip detail below — everything else remains the same, and your checked items will stay checked (manually added items not in your template may be removed).
      </p>
      <div class="space-y-3">
        <div class="grid grid-cols-2 gap-2">
          <div>
            <label class="text-[11px] font-semibold text-slate-400 uppercase">Start Date</label>
            <input
              id="rt-start-date"
              type="date"
              onchange="app.onRecreateDateChange()"
              class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs"
            />
          </div>
          <div>
            <label id="rt-days-label" class="text-[11px] font-semibold text-slate-400 uppercase">End Date</label>
            <input
              id="rt-end-date"
              type="date"
              onchange="app.onRecreateDateChange()"
              class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs"
            />
          </div>
        </div>
        <div class="grid grid-cols-2 gap-2">
          <div>
            <label class="text-[11px] font-semibold text-slate-400 uppercase">Vehicle & Rental Car</label>
            <select
              id="rt-car"
              onchange="app.onRecreateCarChange()"
              class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs"
            >
              <option value="rental_car">Flight + Rental Car</option>
              <option value="no_car">Flight (No Rental Car)</option>
              <option value="own_car">Personal Car (Driving Own)</option>
            </select>
          </div>
          <div>
            <label class="text-[11px] font-semibold text-slate-400 uppercase">Trip Purpose</label>
            <select
              id="rt-purpose"
              class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs"
            >
              <option value="work">Work / Business</option>
              <option value="leisure">Leisure / Personal</option>
            </select>
          </div>
        </div>
        <div id="rt-luggage-box">
          <label class="text-[11px] font-semibold text-slate-400 uppercase">Luggage / Bags</label>
          <select
            id="rt-luggage"
            class="mt-1 w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-xl px-3 py-2 text-xs"
          >
            <option value="carry_on">Carry-On + Shoulder Bag</option>
            <option value="checked_bag">1 Check-In Bag + Carry-On</option>
            <option value="two_checked_bags">2 Check-In Bags + Carry-On</option>
            <option value="personal_only">Shoulder Bag Only</option>
          </select>
        </div>
      </div>
      <div class="flex justify-end gap-2 pt-2">
        <button type="button" onclick="app.closeModals()" class="surface-subtle px-3.5 py-2 rounded-xl text-xs text-slate-300">Cancel</button>
        <button type="button" id="rt-submit-btn" onclick="app.confirmRecreateTrip()" class="accent-bg px-4 py-2 rounded-xl text-xs font-semibold transition">Recreate</button>
      </div>
    </div>
  </div>

  <script>
    const THEMES = {
      emerald: { accent: "#10b981", soft: "rgba(16, 185, 129, 0.14)", border: "rgba(16, 185, 129, 0.38)" },
      sky:     { accent: "#0ea5e9", soft: "rgba(14, 165, 233, 0.14)", border: "rgba(14, 165, 233, 0.38)" },
      violet:  { accent: "#8b5cf6", soft: "rgba(139, 92, 246, 0.14)", border: "rgba(139, 92, 246, 0.38)" },
      amber:   { accent: "#f59e0b", soft: "rgba(245, 158, 11, 0.14)", border: "rgba(245, 158, 11, 0.38)" },
      rose:    { accent: "#f43f5e", soft: "rgba(244, 63, 94, 0.14)",  border: "rgba(244, 63, 94, 0.38)" },
    };

    const CATEGORY_ORDER = [
      "On Me",
      "Docs",
      "Electronics",
      "Clothes",
      "Toiletry",
      "Others",
    ];

    const CONDITION_ORDER = [
      "Always",
      "Only when taking personal car",
      "Only when working",
      "Only when taking rental car",
      "Only for international trips",
      "Only when flying",
      "If expected to be cold",
      "If rain expected",
      "If sunny",
    ];

    const CONDITION_LABELS = {
      always: "Always",
      default: "Always",
      own_car: "Only when taking personal car",
      personal_car: "Only when taking personal car",
      work: "Only when working",
      rental_car: "Only when taking rental car",
      international: "Only for international trips",
      flight: "Only when flying",
      carry_on: "Only when flying",
      checked_bag: "Only when flying",
      cold: "If expected to be cold",
      rain: "If rain expected",
      sunny: "If sunny",
    };

    function formatCondition(raw) {
      const trimmed = String(raw || "").trim();
      if (!trimmed) return "Always";
      return CONDITION_LABELS[trimmed.toLowerCase()] || trimmed;
    }

    class PackTrackClient {
      constructor() {
        this.googleIdToken = localStorage.getItem("packtrack_google_id_token") || "";
        this.userEmail = localStorage.getItem("packtrack_user_email") || "";
        const rawStored = (this.userEmail || localStorage.getItem("packtrack_user_id") || "").trim();
        this.userId = rawStored && rawStored !== "default" ? rawStored.split("@")[0] : "";
        this.state = { userId: this.userId, email: this.userEmail || this.userId, trips: [], custom_rules: [] };
        this.activeTripIdx = null;
        this.currentScreen = "home"; // "home" | "trip" | "template"
        this.templateGrouping = "condition"; // "condition" | "category"
        this.editingRuleIdx = null;

        const savedHue = localStorage.getItem("packtrack_accent_hue");
        if (savedHue !== null) {
          this.setAccentHue(savedHue);
        } else {
          this.setAccentHue(160);
        }
        const savedTempUnit = localStorage.getItem("packtrack_temp_unit") || "F";
        this.setTempUnit(savedTempUnit);
        this.initAuthAndLoad();

        document.addEventListener("visibilitychange", () => {
          if (!document.hidden && this.googleIdToken) {
            this.loadState(false);
          }
        });

        window.history.replaceState({ screen: "home" }, "");
        window.addEventListener("popstate", () => {
          if (this.handleBackAction(true)) {
            window.history.pushState({ screen: this.currentScreen }, "");
          }
        });
        document.addEventListener("keydown", (e) => {
          if (e.key === "Escape") {
            this.handleBackAction(false);
          }
        });
      }

      pushHistoryState(label = "") {
        try {
          window.history.pushState({ screen: this.currentScreen, label }, "");
        } catch (_) {}
      }

      isDrawerOpen() {
        const drawer = document.getElementById("settings-drawer");
        return Boolean(drawer && !drawer.classList.contains("-translate-x-full"));
      }

      closeActiveModals() {
        let closedAny = false;
        for (const id of ["add-item-modal", "edit-item-modal", "add-rule-modal", "recreate-trip-modal", "delete-trip-modal", "delete-rule-modal", "new-trip-modal"]) {
          const el = document.getElementById(id);
          if (el && !el.classList.contains("hidden")) {
            el.classList.add("hidden");
            closedAny = true;
          }
        }
        return closedAny;
      }

      showToast(msg) {
        let toast = document.getElementById("packtrack-toast");
        if (!toast) {
          toast = document.createElement("div");
          toast.id = "packtrack-toast";
          toast.className =
            "fixed bottom-20 left-1/2 -translate-x-1/2 z-50 surface-card border accent-pill px-4 py-2 rounded-xl text-xs font-semibold shadow-xl transition-opacity duration-200 pointer-events-none";
          document.body.appendChild(toast);
        }
        toast.textContent = msg;
        toast.style.opacity = "1";
        clearTimeout(this._toastTimer);
        this._toastTimer = setTimeout(() => {
          if (toast) toast.style.opacity = "0";
        }, 2600);
      }

      stepBackInterview() {
        if (!this.iv || this.iv.step === "CREATING") return false;
        switch (this.iv.step) {
          case "WORK_PURPOSE":
            this.iv.step = this.iv.carMode === "own_car" ? "FLIGHT_OR_CAR" : "BAGS";
            this.render();
            return true;
          case "BAGS":
            this.iv.step = "RENTAL_CAR";
            this.render();
            return true;
          case "RENTAL_CAR":
            this.iv.step = "FLIGHT_OR_CAR";
            this.render();
            return true;
          case "FLIGHT_OR_CAR":
            this.iv.step = "EXACT_DATES";
            this.render();
            return true;
          case "EXACT_DATES":
            this.iv.step = "DESTINATION";
            this.render();
            return true;
          default:
            this.navigateHome();
            return true;
        }
      }

      handleBackAction(fromPopState = false) {
        if (this.closeActiveModals()) return true;
        if (this.isDrawerOpen()) {
          this.closeDrawer();
          return true;
        }
        if (this.currentScreen === "interview") {
          return this.stepBackInterview();
        }
        if (this.currentScreen !== "home") {
          this.navigateHome();
          return true;
        }
        return false;
      }

      decodeJwtPayload(token) {
        try {
          const parts = String(token || "").split(".");
          if (parts.length < 2) return null;
          const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
          return JSON.parse(atob(b64));
        } catch (_) {
          return null;
        }
      }

      isGoogleTokenValid(tokenOverride) {
        const tok = tokenOverride || this.googleIdToken;
        if (!tok) return false;
        const payload = this.decodeJwtPayload(tok);
        if (!payload || !payload.email) return false;
        const issuedAtSec = Number(payload.iat || payload.exp || 0);
        const tenYearsMs = 3650 * 24 * 60 * 60 * 1000;
        if (issuedAtSec > 0) {
          return issuedAtSec * 1000 + tenYearsMs > Date.now();
        }
        return true;
      }

      getHeaders(extra = {}) {
        const headers = {
          "Content-Type": "application/json",
          ...extra,
        };
        if (this.googleIdToken) {
          headers["Authorization"] = "Bearer " + this.googleIdToken;
        }
        return headers;
      }

      async initAuthAndLoad() {
        await this.ensureGoogleSignIn();
        await this.loadState();
      }

      ensureGoogleSignIn(initialError = "") {
        if (this.isGoogleTokenValid() && !initialError) {
          const payload = this.decodeJwtPayload(this.googleIdToken);
          if (payload && payload.email) {
            this.userEmail = String(payload.email).trim().toLowerCase();
            this.userId = this.userEmail.split("@")[0];
            localStorage.setItem("packtrack_user_email", this.userEmail);
            localStorage.setItem("packtrack_user_id", this.userId);
          }
          return Promise.resolve(this.userId);
        }

        return new Promise((resolve) => {
          let modal = document.getElementById("packtrack-auth-modal");
          if (modal) modal.remove();
          modal = document.createElement("div");
          modal.id = "packtrack-auth-modal";
          modal.className = "fixed inset-0 z-50 bg-[#0b0f17]/95 backdrop-blur-md flex items-center justify-center p-4";
          modal.innerHTML =
            '<div class="surface-card rounded-2xl p-6 w-full max-w-sm border border-emerald-500/30 space-y-4 shadow-2xl text-center">' +
            '<div>' +
            '<div class="text-3xl mb-2">🎒</div>' +
            '<h2 class="text-base font-bold text-white">Sign in to PackTrack</h2>' +
            '<p class="text-xs text-slate-400 mt-1">Sign in with your allowlisted Google account to access your packing checklists:</p>' +
            '</div>' +
            '<div id="packtrack-google-btn" class="flex justify-center py-2 min-h-[48px] items-center">' +
            '<span class="text-xs text-slate-400">Loading Google Sign-In...</span>' +
            '</div>' +
            '<div id="packtrack-auth-error" class="' +
            (initialError ? "" : "hidden ") +
            'text-xs text-rose-400 font-medium bg-rose-500/10 border border-rose-500/30 rounded-xl p-2.5">' +
            (initialError || "") +
            '</div>' +
            '</div>';
          document.body.appendChild(modal);

          const errEl = document.getElementById("packtrack-auth-error");
          const btnContainer = document.getElementById("packtrack-google-btn");

          const initGoogle = async () => {
            try {
              const cfgRes = await fetch("/api/auth-config", { cache: "no-store" });
              const cfg = await cfgRes.json();
              const clientId = cfg.googleClientId || "";
              let tries = 0;
              while (!(window.google && window.google.accounts && window.google.accounts.id) && tries < 40) {
                await new Promise((r) => setTimeout(r, 150));
                tries++;
              }
              if (!(window.google && window.google.accounts && window.google.accounts.id)) {
                if (errEl) {
                  errEl.textContent = "Unable to load Google Sign-In SDK. Check your connection and reload.";
                  errEl.classList.remove("hidden");
                }
                return;
              }
              window.google.accounts.id.initialize({
                client_id: clientId,
                callback: (resp) => {
                  const token = resp && resp.credential ? resp.credential : "";
                  const payload = this.decodeJwtPayload(token);
                  if (!token || !payload || !payload.email) {
                    if (errEl) {
                      errEl.textContent = "Google Sign-In did not return a valid email token.";
                      errEl.classList.remove("hidden");
                    }
                    return;
                  }
                  this.googleIdToken = token;
                  this.userEmail = String(payload.email).trim().toLowerCase();
                  this.userId = this.userEmail.split("@")[0];
                  localStorage.setItem("packtrack_google_id_token", this.googleIdToken);
                  localStorage.setItem("packtrack_user_email", this.userEmail);
                  localStorage.setItem("packtrack_user_id", this.userId);
                  modal.remove();
                  resolve(this.userId);
                },
              });
              if (btnContainer) {
                btnContainer.innerHTML = "";
                window.google.accounts.id.renderButton(btnContainer, {
                  theme: "filled_black",
                  size: "large",
                  shape: "pill",
                  text: "signin_with",
                  width: 260,
                });
              }
              try {
                window.google.accounts.id.prompt();
              } catch (_) {}
            } catch (_) {
              if (errEl) {
                errEl.textContent = "Failed to initialize Google Sign-In.";
                errEl.classList.remove("hidden");
              }
            }
          };

          initGoogle();
        });
      }

      signOutGoogle() {
        this.googleIdToken = "";
        this.userEmail = "";
        this.userId = "";
        localStorage.removeItem("packtrack_google_id_token");
        localStorage.removeItem("packtrack_user_email");
        localStorage.removeItem("packtrack_user_id");
        try {
          if (window.google && window.google.accounts && window.google.accounts.id) {
            window.google.accounts.id.disableAutoSelect();
          }
        } catch (_) {}
        window.location.reload();
      }

      setTempUnit(unit) {
        this.tempUnit = String(unit || "F").toUpperCase() === "C" ? "C" : "F";
        localStorage.setItem("packtrack_temp_unit", this.tempUnit);
        const fBtn = document.getElementById("temp-unit-f");
        const cBtn = document.getElementById("temp-unit-c");
        if (fBtn && cBtn) {
          fBtn.className = this.tempUnit === "F"
            ? "py-1.5 rounded-lg text-xs font-semibold accent-bg transition"
            : "py-1.5 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition";
          cBtn.className = this.tempUnit === "C"
            ? "py-1.5 rounded-lg text-xs font-semibold accent-bg transition"
            : "py-1.5 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition";
        }
        if (this.state) this.render();
      }

      formatTempRange(lowF = 52, highF = 68) {
        const parsedL = Number(lowF);
        const parsedH = Number(highF);
        const lF = Number.isFinite(parsedL) && lowF !== null && lowF !== "" ? parsedL : 52;
        const hF = Number.isFinite(parsedH) && highF !== null && highF !== "" ? parsedH : 68;
        if (this.tempUnit === "C") {
          const lC = Math.round(((lF - 32) * 5) / 9);
          const hC = Math.round(((hF - 32) * 5) / 9);
          return \`\${lC}°–\${hC}°C\`;
        }
        return \`\${Math.round(lF)}°–\${Math.round(hF)}°F\`;
      }

      getLocalTodayIso() {
        const now = new Date();
        const y = now.getFullYear();
        const m = String(now.getMonth() + 1).padStart(2, "0");
        const d = String(now.getDate()).padStart(2, "0");
        return \`\${y}-\${m}-\${d}\`;
      }

      daysUntilStart(rawStartDate) {
        const s = String(rawStartDate || "").trim().slice(0, 10);
        if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return null;
        const [y, m, d] = s.split("-").map(Number);
        const startMidnight = new Date(y, m - 1, d);
        const now = new Date();
        const todayMidnight = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        return Math.round((startMidnight - todayMidnight) / (1000 * 60 * 60 * 24));
      }

      formatRelativeStart(rawStartDate) {
        const diff = this.daysUntilStart(rawStartDate);
        if (diff === null) return "Today";
        if (diff === 0) return "Today";
        if (diff === 1) return "Tomorrow";
        if (diff >= 2 && diff <= 29) return \`In \${diff} days\`;
        if (diff >= 30 && diff <= 44) return "In 1 month";
        if (diff >= 45 && diff <= 364) {
          const months = Math.max(1, Math.round(diff / 30));
          return months === 1 ? "In 1 month" : \`In \${months} months\`;
        }
        if (diff >= 365) {
          const years = Math.max(1, Math.round(diff / 365));
          return years === 1 ? "In 1 year" : \`In \${years} years\`;
        }
        if (diff === -1) return "Yesterday";
        if (diff > -30) return \`\${-diff} days ago\`;
        const monthsAgo = Math.max(1, Math.round(-diff / 30));
        return monthsAgo === 1 ? "1 month ago" : \`\${monthsAgo} months ago\`;
      }

      sortKeyClosestToFarthest(rawStartDate) {
        const diff = this.daysUntilStart(rawStartDate);
        if (diff === null) return 0;
        return diff >= 0 ? diff : 1000000 + Math.abs(diff);
      }

      formatDateRange(rawStartDate, durationDays, rawEndDate) {
        const sClean = String(rawStartDate || "").trim().slice(0, 10);
        let sDate;
        if (/^\d{4}-\d{2}-\d{2}$/.test(sClean)) {
          const [y, m, d] = sClean.split("-").map(Number);
          sDate = new Date(Date.UTC(y, m - 1, d));
        } else {
          const now = new Date();
          sDate = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()));
        }
        const days = Math.max(1, Number(durationDays) || 1);
        const eClean = String(rawEndDate || "").trim().slice(0, 10);
        let eDate;
        if (/^\d{4}-\d{2}-\d{2}$/.test(eClean)) {
          const [y, m, d] = eClean.split("-").map(Number);
          eDate = new Date(Date.UTC(y, m - 1, d));
        } else {
          eDate = new Date(sDate.getTime() + days * 86400000);
        }
        const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
        const sm = months[sDate.getUTCMonth()];
        const sd = sDate.getUTCDate();
        const sy = sDate.getUTCFullYear();
        const em = months[eDate.getUTCMonth()];
        const ed = eDate.getUTCDate();
        const ey = eDate.getUTCFullYear();
        if (sy !== ey) return \`\${sm} \${sd}, \${sy} – \${em} \${ed}, \${ey}\`;
        if (sm === em) return \`\${sm} \${sd}–\${ed}\`;
        return \`\${sm} \${sd} – \${em} \${ed}\`;
      }

      isPlugInfoNeeded(country, countryCode, plugTypes) {
        const code = String(countryCode || "").trim().toUpperCase();
        if (["US", "USA", "IN", "IND"].includes(code)) return false;
        const c = String(country || "").trim().toLowerCase();
        if (["united states", "united states of america", "usa", "us", "u.s.", "u.s.a.", "india", "in", "bharat"].includes(c)) {
          return false;
        }
        const p = String(plugTypes || "").trim();
        if (!c && !code && (!p || p === "Type A/B" || p.startsWith("Type A/B (120V)") || p.startsWith("Type C/D/M"))) {
          return false;
        }
        return Boolean(p);
      }

      setAccentHue(hueVal) {
        const parsed = Number(hueVal);
        const h = Math.max(0, Math.min(360, Number.isFinite(parsed) && String(hueVal).trim() !== "" ? parsed : 160));
        this.accentHue = h;
        document.documentElement.style.setProperty("--accent", \`hsl(\${h}, 82%, 46%)\`);
        document.documentElement.style.setProperty("--accent-soft", \`hsla(\${h}, 82%, 46%, 0.14)\`);
        document.documentElement.style.setProperty("--accent-border", \`hsla(\${h}, 82%, 46%, 0.38)\`);
        localStorage.setItem("packtrack_accent_hue", String(h));
        const slider = document.getElementById("accent-hue-slider");
        if (slider && Number(slider.value) !== h) {
          slider.value = String(h);
        }
      }

      handleNavButtonClick() {
        if (this.isDrawerOpen()) {
          this.closeDrawer();
        } else if (this.currentScreen === "home") {
          this.openDrawer();
        } else if (this.currentScreen === "interview") {
          this.stepBackInterview();
        } else {
          this.navigateHome();
        }
      }

      handleFabClick() {
        if (this.currentScreen === "trip") {
          this.openAddItemModal();
        } else if (this.currentScreen === "template") {
          this.openAddRuleModal();
        } else {
          this.openNewTripModal();
        }
      }

      openDrawer() {
        document.getElementById("drawer-email-input").value =
          this.userEmail || (this.userId === "default" ? "" : (this.state.email || this.userId));
        document.getElementById("drawer-backdrop").classList.remove("hidden");
        document.getElementById("settings-drawer").classList.remove("-translate-x-full");
        this.pushHistoryState("drawer");
      }

      closeDrawer() {
        document.getElementById("drawer-backdrop").classList.add("hidden");
        document.getElementById("settings-drawer").classList.add("-translate-x-full");
      }

      saveUserFromDrawer() {
        this.signOutGoogle();
      }

      openHomeFromDrawer() {
        this.closeDrawer();
        this.navigateHome();
      }

      openTemplateFromDrawer() {
        this.closeDrawer();
        this.currentScreen = "template";
        this.pushHistoryState("template");
        this.render();
      }

      navigateHome() {
        this.currentScreen = "home";
        this.activeTripIdx = null;
        this.render();
      }

      openTripDetail(idx) {
        this.activeTripIdx = idx;
        this.currentScreen = "trip";
        this.pushHistoryState("trip");
        this.render();
      }

      async loadState(manual = false) {
        if (!manual && this._isMutatingTrip) return;
        if (manual) {
          document.getElementById("drawer-sync-status").textContent = "Syncing...";
        }
        try {
          const resp = await fetch("/api/state", { headers: this.getHeaders() });
          if (resp.status === 401 || resp.status === 403) {
            const errBody = await resp.json().catch(() => ({}));
            this.googleIdToken = "";
            localStorage.removeItem("packtrack_google_id_token");
            try {
              if (window.google && window.google.accounts && window.google.accounts.id) {
                window.google.accounts.id.disableAutoSelect();
              }
            } catch (_) {}
            await this.ensureGoogleSignIn(errBody.error || "Please sign in with an allowlisted Google account.");
            return this.loadState(manual);
          }
          if (resp.ok) {
            this.state = await resp.json();
            if (this.state && this.state.session_token && this.state.session_token !== this.googleIdToken) {
              this.googleIdToken = this.state.session_token;
              localStorage.setItem("packtrack_google_id_token", this.googleIdToken);
            }
          }
        } catch (_) {}
        if (manual) {
          this.closeDrawer();
        }
        this.render();
      }

      async pushState() {
        this.render();
        if (!this.googleIdToken) return;
        const reqPromise = (async () => {
          try {
            await fetch("/api/state", {
              method: "POST",
              headers: this.getHeaders(),
              body: JSON.stringify(this.state),
            });
          } catch (_) {}
        })();
        this._lastPushPromise = reqPromise;
        return reqPromise;
      }

      getAllCategories() {
        const stored = (localStorage.getItem("packtrack_custom_categories") || "")
          .split("|")
          .map((c) => c.trim())
          .filter(Boolean);
        const fromRules = (this.state.custom_rules || []).map((r) => String(r.category || "").trim()).filter(Boolean);
        const fromTrips = (this.state.trips || [])
          .flatMap((t) => (t.items || []).map((i) => String(i.category || "").trim()))
          .filter(Boolean);
        const result = [...CATEGORY_ORDER];
        for (const cat of [...stored, ...fromRules, ...fromTrips]) {
          if (!result.some((existing) => existing.toLowerCase() === cat.toLowerCase())) {
            result.push(cat);
          }
        }
        return result;
      }

      populateCategorySelect(selectId, selectedVal = "On Me") {
        const sel = document.getElementById(selectId);
        if (!sel) return;
        const cats = this.getAllCategories();
        if (selectedVal && !cats.some((c) => c.toLowerCase() === selectedVal.toLowerCase())) {
          cats.push(selectedVal);
        }
        sel.innerHTML = "";
        for (const c of cats) {
          const opt = document.createElement("option");
          opt.value = c;
          opt.textContent = c;
          if (c.toLowerCase() === String(selectedVal || "").toLowerCase()) {
            opt.selected = true;
          }
          sel.appendChild(opt);
        }
        const addOpt = document.createElement("option");
        addOpt.value = "__new__";
        addOpt.textContent = "+ Add Category...";
        sel.appendChild(addOpt);
      }

      toggleNewCategoryBox(prefix) {
        const box = document.getElementById(\`\${prefix}-new-cat-box\`);
        const input = document.getElementById(\`\${prefix}-new-cat-input\`);
        if (!box) return;
        box.classList.toggle("hidden");
        if (!box.classList.contains("hidden") && input) {
          input.value = "";
          input.focus();
        }
      }

      handleCategorySelectChange(prefix) {
        const sel = document.getElementById(\`\${prefix}-cat\`);
        const box = document.getElementById(\`\${prefix}-new-cat-box\`);
        const input = document.getElementById(\`\${prefix}-new-cat-input\`);
        if (sel && sel.value === "__new__") {
          if (box) box.classList.remove("hidden");
          if (input) {
            input.value = "";
            input.focus();
          }
        }
      }

      saveNewCategory(prefix) {
        const input = document.getElementById(\`\${prefix}-new-cat-input\`);
        const box = document.getElementById(\`\${prefix}-new-cat-box\`);
        const newCat = String(input?.value || "").trim();
        if (!newCat) return;
        const stored = (localStorage.getItem("packtrack_custom_categories") || "")
          .split("|")
          .map((c) => c.trim())
          .filter(Boolean);
        if (!stored.some((c) => c.toLowerCase() === newCat.toLowerCase())) {
          stored.push(newCat);
          localStorage.setItem("packtrack_custom_categories", stored.join("|"));
        }
        if (box) box.classList.add("hidden");
        if (input) input.value = "";
        this.populateCategorySelect(\`\${prefix}-cat\`, newCat);
      }

      openNewTripModal() {
        const todayIso = this.getLocalTodayIso();
        const endIso = this.addDaysIso(todayIso, 4);
        this.iv = {
          step: "DESTINATION",
          destination: "",
          startDate: todayIso,
          endDate: endIso,
          days: 4,
          carMode: "rental_car",
          luggageSpace: "carry_on",
          isWork: true,
        };
        this.currentScreen = "interview";
        this.pushHistoryState("interview-destination");
        this.render();
      }

      addDaysIso(baseIso, offsetDays) {
        const [y, m, d] = String(baseIso).split("-").map(Number);
        const dt = new Date(Date.UTC(y, m - 1, d + offsetDays));
        return dt.toISOString().slice(0, 10);
      }

      calcDaysBetween(startIso, endIso) {
        const [y1, m1, d1] = String(startIso).split("-").map(Number);
        const [y2, m2, d2] = String(endIso).split("-").map(Number);
        const ms1 = Date.UTC(y1, m1 - 1, d1);
        const ms2 = Date.UTC(y2, m2 - 1, d2);
        const diff = Math.round((ms2 - ms1) / 86400000);
        return Math.max(1, Math.min(30, diff || 1));
      }

      answerInterviewDestination(cityRaw) {
        const clean = String(cityRaw ?? document.getElementById("iv-dest-input")?.value ?? "").trim();
        if (!clean) return;
        this.iv.destination = clean.charAt(0).toUpperCase() + clean.slice(1);
        this.iv.step = "EXACT_DATES";
        this.pushHistoryState("interview-dates");
        this.render();
      }

      onInterviewDateChange() {
        const sEl = document.getElementById("iv-start-date");
        const eEl = document.getElementById("iv-end-date");
        if (!sEl || !eEl) return;
        this.iv.startDate = sEl.value || this.iv.startDate;
        const maxEndIso = this.addDaysIso(this.iv.startDate, 30);
        if (!eEl.value || eEl.value <= this.iv.startDate) {
          this.iv.endDate = this.addDaysIso(this.iv.startDate, 4);
        } else if (eEl.value > maxEndIso) {
          this.iv.endDate = maxEndIso;
        } else {
          this.iv.endDate = eEl.value;
        }
        this.iv.days = this.calcDaysBetween(this.iv.startDate, this.iv.endDate);
        this.render();
      }

      setInterviewDatePreset(startIso, endIso) {
        this.iv.startDate = startIso;
        this.iv.endDate = endIso;
        this.iv.days = this.calcDaysBetween(startIso, endIso);
        this.render();
      }

      confirmInterviewDates() {
        this.iv.days = this.calcDaysBetween(this.iv.startDate, this.iv.endDate);
        this.iv.step = "FLIGHT_OR_CAR";
        this.pushHistoryState("interview-transport");
        this.render();
      }

      answerInterviewTransport(mode) {
        if (mode === "own_car") {
          this.iv.carMode = "own_car";
          this.iv.luggageSpace = "two_checked_bags";
          this.iv.step = "WORK_PURPOSE";
        } else {
          this.iv.step = "RENTAL_CAR";
        }
        this.pushHistoryState("interview-next");
        this.render();
      }

      answerInterviewRental(wantsRental) {
        this.iv.carMode = wantsRental ? "rental_car" : "no_car";
        this.iv.step = "BAGS";
        this.pushHistoryState("interview-bags");
        this.render();
      }

      answerInterviewBags(bagTag) {
        this.iv.luggageSpace = bagTag;
        this.iv.step = "WORK_PURPOSE";
        this.pushHistoryState("interview-work");
        this.render();
      }

      async answerInterviewWork(isWork) {
        this.iv.isWork = Boolean(isWork);
        this.iv.step = "CREATING";
        this.render();
        try {
          const tags = [
            this.iv.isWork ? "work" : "leisure",
            this.iv.carMode,
            this.iv.luggageSpace,
          ];
          const resp = await fetch("/api/trips/create", {
            method: "POST",
            headers: this.getHeaders(),
            body: JSON.stringify({
              destination: this.iv.destination || "Seattle",
              durationDays: this.iv.days || 4,
              startDate: this.iv.startDate,
              endDate: this.iv.endDate,
              tags,
              geminiApiKey: localStorage.getItem("packtrack_gemini_key") || "",
            }),
          });
          if (resp.ok) {
            this.state = await resp.json();
            this.openTripDetail(0);
          } else {
            this.navigateHome();
          }
        } catch (_) {
          this.navigateHome();
        }
      }

      openAddItemModal() {
        const trip = this.state.trips?.[this.activeTripIdx];
        document.getElementById("ai-name").value = "";
        document.getElementById("ai-qty").value = "1";
        document.getElementById("ai-doorway").checked = Boolean(trip && trip.tripMode === "DOORWAY");
        document.getElementById("ai-save-template").checked = false;
        document.getElementById("ai-new-cat-box")?.classList.add("hidden");
        this.populateCategorySelect("ai-cat", trip && trip.tripMode === "DOORWAY" ? "On Me" : "Others");
        document.getElementById("add-item-modal").classList.remove("hidden");
        this.pushHistoryState("add-item-modal");
      }

      openEditItemModal(itemIdx) {
        const trip = this.state.trips?.[this.activeTripIdx];
        const item = trip?.items?.[itemIdx];
        if (!item) return;
        this.editingItemIdx = itemIdx;
        document.getElementById("ei-name").value = item.name || "";
        document.getElementById("ei-qty").value = String(Math.max(1, Number(item.quantity) || 1));
        const rawBag = String(item.bagLocation || "Carry-On").toLowerCase();
        document.getElementById("ei-bag").value = rawBag.includes("check")
          ? "Check-In"
          : rawBag.includes("carry")
          ? "Carry-On"
          : "Personal";
        document.getElementById("ei-new-cat-box")?.classList.add("hidden");
        this.populateCategorySelect("ei-cat", item.category || "Others");
        document.getElementById("ei-doorway").checked = Boolean(item.isDoorwayCritical);

        const promoteBtn = document.getElementById("ei-promote-btn");
        if (promoteBtn) {
          const alreadyInTemplate = (this.state.custom_rules || []).some(
            (r) => String(r.name || "").trim().toLowerCase() === String(item.name || "").trim().toLowerCase()
          );
          promoteBtn.classList.toggle("hidden", alreadyInTemplate);
        }

        document.getElementById("edit-item-modal").classList.remove("hidden");
        this.pushHistoryState("edit-item-modal");
      }

      submitEditItem() {
        const trip = this.state.trips?.[this.activeTripIdx];
        const item = trip?.items?.[this.editingItemIdx];
        if (!item) return;
        const name = document.getElementById("ei-name").value.trim();
        if (name) item.name = name;
        item.quantity = Math.max(1, parseInt(document.getElementById("ei-qty").value, 10) || 1);
        item.bagLocation = document.getElementById("ei-bag").value || "Carry-On";
        const pendingCat = document.getElementById("ei-new-cat-input")?.value?.trim();
        if (pendingCat) {
          this.saveNewCategory("ei");
        }
        const rawCat = document.getElementById("ei-cat").value;
        item.category = rawCat === "__new__" ? (pendingCat || "Others") : rawCat;
        item.isDoorwayCritical = Boolean(document.getElementById("ei-doorway").checked);
        this.editingItemIdx = null;
        this.closeModals();
        this.pushState();
        this.showToast(\`Updated "\${item.name}"\`);
      }

      promoteEditedItemToTemplate() {
        if (this.editingItemIdx === null || this.editingItemIdx === undefined) return;
        this.promoteItemToTemplate(this.editingItemIdx);
        const promoteBtn = document.getElementById("ei-promote-btn");
        if (promoteBtn) promoteBtn.classList.add("hidden");
        this.showToast("★ Saved to Default Packing Template");
      }

      deleteEditedItem() {
        if (this.editingItemIdx === null || this.editingItemIdx === undefined) return;
        const idx = this.editingItemIdx;
        this.editingItemIdx = null;
        this.closeModals();
        this.deleteItem(idx);
      }

      openAddRuleModal() {
        this.editingRuleIdx = null;
        document.getElementById("ar-modal-title").textContent = "Add Item";
        document.getElementById("ar-name").value = "";
        document.getElementById("ar-mod").value = "";
        document.getElementById("ar-new-cat-box")?.classList.add("hidden");
        this.populateCategorySelect("ar-cat", "On Me");
        document.getElementById("ar-bag").value = "Personal";
        document.getElementById("ar-scale-select").value = "fixed";
        document.getElementById("add-rule-modal").classList.remove("hidden");
        this.pushHistoryState("add-rule-modal");
      }

      openEditRuleModal(idx) {
        const rule = this.state.custom_rules?.[idx];
        if (!rule) return;
        this.editingRuleIdx = idx;
        document.getElementById("ar-modal-title").textContent = "Edit Item";
        document.getElementById("ar-name").value = rule.name || "";
        const rawMod = String(rule.moduleKey || "Always").trim();
        const displayMod = CONDITION_LABELS[rawMod.toLowerCase()] || rawMod;
        document.getElementById("ar-mod").value = displayMod.toLowerCase() === "always" ? "" : displayMod;
        document.getElementById("ar-new-cat-box")?.classList.add("hidden");
        this.populateCategorySelect("ar-cat", rule.category || "Others");
        const rawBag = String(rule.bagLocation || "Carry-On").toLowerCase();
        document.getElementById("ar-bag").value = rawBag.includes("check")
          ? "Check-In"
          : rawBag.includes("carry")
          ? "Carry-On"
          : "Personal";
        if (!rule.scaleByDays) {
          document.getElementById("ar-scale-select").value = "fixed";
        } else {
          const r = Number(rule.ratioPerDay) || 1;
          document.getElementById("ar-scale-select").value =
            r <= 0.4 ? "0.35" : r <= 0.6 ? "0.5" : r >= 2.5 ? "3" : r >= 1.5 ? "2" : "1";
        }
        document.getElementById("add-rule-modal").classList.remove("hidden");
      }

      setTemplateGrouping(mode) {
        this.templateGrouping = mode === "category" ? "category" : "condition";
        this.renderTemplateRules();
      }

      closeModals() {
        for (const id of ["new-trip-modal", "add-item-modal", "edit-item-modal", "add-rule-modal", "delete-trip-modal", "delete-rule-modal", "recreate-trip-modal"]) {
          const el = document.getElementById(id);
          if (el) el.classList.add("hidden");
        }
      }

      openRecreateTripModal() {
        if (this.activeTripIdx === null || !this.state.trips?.[this.activeTripIdx]) return;
        const trip = this.state.trips[this.activeTripIdx];
        const todayIso = this.getLocalTodayIso();
        const rawStart = String(trip.startDate || "").trim().slice(0, 10);
        const startIso = /^\d{4}-\d{2}-\d{2}$/.test(rawStart) ? rawStart : todayIso;
        const curDays = Math.max(1, Math.min(30, Number(trip.durationDays) || 3));
        const rawEnd = String(trip.endDate || "").trim().slice(0, 10);
        const endIso =
          /^\d{4}-\d{2}-\d{2}$/.test(rawEnd) && rawEnd > startIso
            ? rawEnd
            : this.addDaysIso(startIso, curDays);

        const tags = String(trip.tagsCsv || "work,rental_car,carry_on")
          .split(",")
          .map((t) => t.trim().toLowerCase())
          .filter(Boolean);
        const purpose = tags.includes("leisure") && !tags.includes("work") ? "leisure" : "work";
        const carMode =
          tags.includes("own_car") || tags.includes("personal_car")
            ? "own_car"
            : tags.includes("no_car")
            ? "no_car"
            : "rental_car";
        const luggage = tags.includes("two_checked_bags")
          ? "two_checked_bags"
          : tags.includes("checked_bag")
          ? "checked_bag"
          : tags.includes("personal_only")
          ? "personal_only"
          : "carry_on";

        const titleEl = document.getElementById("rt-modal-title");
        if (titleEl) titleEl.textContent = \`Recreate \${trip.destination || "Trip"} Checklist\`;

        const sEl = document.getElementById("rt-start-date");
        const eEl = document.getElementById("rt-end-date");
        if (sEl) sEl.value = startIso;
        if (eEl) {
          eEl.min = startIso;
          eEl.value = endIso;
        }
        const daysLabel = document.getElementById("rt-days-label");
        const calcDays = this.calcDaysBetween(startIso, endIso);
        if (daysLabel) daysLabel.textContent = \`End Date (\${calcDays}d)\`;

        const carEl = document.getElementById("rt-car");
        if (carEl) carEl.value = carMode;

        const purpEl = document.getElementById("rt-purpose");
        if (purpEl) purpEl.value = purpose;

        const lugEl = document.getElementById("rt-luggage");
        if (lugEl) lugEl.value = luggage;

        this.onRecreateCarChange();
        this.pushHistoryState("recreate-trip-modal");
        document.getElementById("recreate-trip-modal").classList.remove("hidden");
      }

      onRecreateDateChange() {
        const sEl = document.getElementById("rt-start-date");
        const eEl = document.getElementById("rt-end-date");
        if (!sEl || !eEl) return;
        const startIso = sEl.value || this.getLocalTodayIso();
        const maxEndIso = this.addDaysIso(startIso, 30);
        eEl.min = startIso;
        if (!eEl.value || eEl.value <= startIso) {
          const trip = this.state.trips?.[this.activeTripIdx];
          const fallbackDays = Math.max(1, Math.min(30, Number(trip?.durationDays) || 3));
          eEl.value = this.addDaysIso(startIso, fallbackDays);
        } else if (eEl.value > maxEndIso) {
          eEl.value = maxEndIso;
        }
        const days = this.calcDaysBetween(startIso, eEl.value);
        const daysLabel = document.getElementById("rt-days-label");
        if (daysLabel) daysLabel.textContent = \`End Date (\${days}d)\`;
      }

      onRecreateCarChange() {
        const carEl = document.getElementById("rt-car");
        const lugBox = document.getElementById("rt-luggage-box");
        const lugEl = document.getElementById("rt-luggage");
        if (!carEl || !lugBox) return;
        if (carEl.value === "own_car") {
          if (lugEl) lugEl.value = "two_checked_bags";
          lugBox.classList.add("hidden");
        } else {
          lugBox.classList.remove("hidden");
        }
      }

      async confirmRecreateTrip() {
        if (this.activeTripIdx === null) return;
        const btn = document.getElementById("rt-submit-btn");
        if (btn) {
          btn.textContent = "Recreating...";
          btn.disabled = true;
        }
        this._isMutatingTrip = true;
        try {
          const sEl = document.getElementById("rt-start-date");
          const eEl = document.getElementById("rt-end-date");
          const carEl = document.getElementById("rt-car");
          const purpEl = document.getElementById("rt-purpose");
          const lugEl = document.getElementById("rt-luggage");

          const startDate = sEl?.value || "";
          const endDate = eEl?.value || "";
          const durationDays =
            startDate && endDate ? this.calcDaysBetween(startDate, endDate) : undefined;
          const carMode = carEl?.value || "rental_car";
          const purpose = purpEl?.value || "work";
          const luggage =
            carMode === "own_car" ? "two_checked_bags" : lugEl?.value || "carry_on";

          const currentTrip = this.state.trips?.[this.activeTripIdx];
          if (currentTrip) {
            if (startDate) currentTrip.startDate = startDate;
            if (endDate) currentTrip.endDate = endDate;
            if (durationDays) currentTrip.durationDays = durationDays;
            currentTrip.tagsCsv = [purpose, carMode, luggage].join(",");
            this.render();
          }
          if (this._lastPushPromise) {
            await Promise.race([this._lastPushPromise, new Promise((r) => setTimeout(r, 1500))]);
          }
          const resp = await fetch("/api/trips/recreate", {
            method: "POST",
            headers: this.getHeaders(),
            body: JSON.stringify({
              tripIndex: this.activeTripIdx,
              destination: currentTrip?.destination || "",
              startDate,
              endDate,
              durationDays,
              tags: [purpose, carMode, luggage],
              tripMode: currentTrip?.tripMode || "OUTBOUND",
              existingItems: currentTrip?.items || [],
              geminiApiKey: localStorage.getItem("packtrack_gemini_key") || "",
            }),
          });
          if (resp.ok) {
            this.state = await resp.json();
            if (startDate && this.state.trips?.[this.activeTripIdx]) {
              this.state.trips[this.activeTripIdx].startDate = startDate;
              if (endDate) this.state.trips[this.activeTripIdx].endDate = endDate;
              if (durationDays) this.state.trips[this.activeTripIdx].durationDays = durationDays;
            }
            this.closeModals();
            this.render();
            this.showToast("✓ Checklist recreated (checked items preserved)");
          } else {
            this.showToast("Could not recreate checklist — please try again");
          }
        } catch (_) {
          this.showToast("Network error while recreating checklist");
        } finally {
          this._isMutatingTrip = false;
          if (btn) {
            btn.textContent = "Recreate";
            btn.disabled = false;
          }
        }
      }

      async submitNewTrip() {
        const btn = document.getElementById("nt-submit-btn");
        btn.textContent = "Creating...";
        btn.disabled = true;
        try {
          const destination = document.getElementById("nt-dest").value.trim() || "Seattle";
          const durationDays = parseInt(document.getElementById("nt-days").value, 10) || 5;
          const startDate = document.getElementById("nt-start-date")?.value || new Date().toISOString().slice(0, 10);
          const tags = [
            document.getElementById("nt-purpose").value,
            document.getElementById("nt-car").value,
            document.getElementById("nt-luggage").value,
          ];
          const resp = await fetch("/api/trips/create", {
            method: "POST",
            headers: this.getHeaders(),
            body: JSON.stringify({
              destination,
              durationDays,
              startDate,
              tags,
              geminiApiKey: localStorage.getItem("packtrack_gemini_key") || "",
            }),
          });
          if (resp.ok) {
            this.state = await resp.json();
            this.closeModals();
            this.openTripDetail(0);
          }
        } finally {
          btn.textContent = "Create Trip";
          btn.disabled = false;
        }
      }

      deleteTrip(idx, ev) {
        if (ev) ev.stopPropagation();
        const trip = this.state.trips?.[idx];
        if (!trip) return;
        this.pendingDeleteTripIdx = idx;
        document.getElementById("dt-title").textContent = \`Delete \${trip.destination}?\`;
        document.getElementById("dt-desc").textContent = \`Remove this \${trip.durationDays}-day trip checklist?\`;
        document.getElementById("delete-trip-modal").classList.remove("hidden");
      }

      confirmDeleteTrip() {
        const idx = this.pendingDeleteTripIdx;
        if (idx === null || idx === undefined) return;
        this.state.trips.splice(idx, 1);
        if (this.currentScreen === "trip" && this.activeTripIdx === idx) {
          this.currentScreen = "home";
          this.activeTripIdx = null;
        } else if (this.activeTripIdx !== null && idx < this.activeTripIdx) {
          this.activeTripIdx -= 1;
        }
        this.pendingDeleteTripIdx = null;
        this.closeModals();
        this.pushState();
      }

      setTripMode(mode) {
        const trip = this.state.trips[this.activeTripIdx];
        if (!trip) return;
        trip.tripMode = mode;
        this.pushState();
      }

      toggleItem(itemIdx) {
        const trip = this.state.trips[this.activeTripIdx];
        if (!trip || !trip.items[itemIdx]) return;
        const item = trip.items[itemIdx];
        if (trip.tripMode === "RETURN_SWEEP") {
          item.packedReturn = !item.packedReturn;
        } else {
          item.packedOutbound = !item.packedOutbound;
        }
        this.pushState();
      }

      deleteItem(itemIdx, ev) {
        if (ev) ev.stopPropagation();
        const trip = this.state.trips[this.activeTripIdx];
        if (!trip || !trip.items?.[itemIdx]) return;
        const item = trip.items[itemIdx];
        const isSuggested = String(item.weatherReason || "").startsWith("[SUGGESTED] ");
        // If user is on the Doorway tab and removes an active item, only remove it from Doorway (keep in Pack list)
        if (!isSuggested && trip.tripMode === "DOORWAY") {
          item.isDoorwayCritical = false;
          this.pushState();
          this.showToast(\`Removed "\${item.name}" from Doorway check (kept in Pack)\`);
          return;
        }
        trip.items.splice(itemIdx, 1);
        this.pushState();
      }

      acceptSuggestedItem(itemIdx, ev) {
        if (ev) ev.stopPropagation();
        const trip = this.state.trips[this.activeTripIdx];
        const item = trip?.items?.[itemIdx];
        if (!item) return;
        const cleanReason = String(item.weatherReason || "").replace(/^\[SUGGESTED\]\s*/i, "").trim();
        item.weatherReason = cleanReason ? \`✨ \${cleanReason}\` : "✨ Gemini suggestion";
        this.pushState();
      }

      promoteItemToTemplate(itemIdx, ev) {
        if (ev) ev.stopPropagation();
        const trip = this.state.trips[this.activeTripIdx];
        const item = trip?.items?.[itemIdx];
        if (!item) return;
        const exists = this.state.custom_rules.some((r) => r.name.toLowerCase() === item.name.toLowerCase());
        if (!exists) {
          this.state.custom_rules.push({
            moduleKey: "Always",
            name: item.name,
            category: item.category || "Others",
            scaleByDays: false,
            ratioPerDay: 1.0,
            isDoorwayCritical: Boolean(item.isDoorwayCritical),
            isHighLossReturn: true,
            bagLocation: item.bagLocation || "Carry-On",
          });
          this.pushState();
        }
      }

      submitAddItem() {
        const trip = this.state.trips[this.activeTripIdx];
        if (!trip) return;
        const name = document.getElementById("ai-name").value.trim();
        if (!name) return;
        const quantity = Math.max(1, parseInt(document.getElementById("ai-qty").value, 10) || 1);
        const bagLocation = document.getElementById("ai-bag").value;
        const pendingCat = document.getElementById("ai-new-cat-input")?.value?.trim();
        if (pendingCat) {
          this.saveNewCategory("ai");
        }
        const rawCat = document.getElementById("ai-cat").value;
        const category = rawCat === "__new__" ? (pendingCat || "Others") : rawCat;
        const isDoorwayCritical = document.getElementById("ai-doorway").checked;
        const saveTemplate = document.getElementById("ai-save-template").checked;

        trip.items.push({
          name,
          category,
          quantity,
          bagLocation,
          packedOutbound: false,
          packedReturn: false,
          isDoorwayCritical,
          isHighLossReturn: true,
          weatherReason: "Custom item",
        });

        if (saveTemplate) {
          const exists = this.state.custom_rules.some((r) => r.name.toLowerCase() === name.toLowerCase());
          if (!exists) {
            this.state.custom_rules.push({
              moduleKey: "Always",
              name,
              category,
              scaleByDays: false,
              ratioPerDay: 1.0,
              isDoorwayCritical,
              isHighLossReturn: true,
              bagLocation,
            });
          }
        }

        this.closeModals();
        this.pushState();
      }

      submitAddRule() {
        const name = document.getElementById("ar-name").value.trim();
        if (!name) return;
        const pendingCat = document.getElementById("ar-new-cat-input")?.value?.trim();
        if (pendingCat) {
          this.saveNewCategory("ar");
        }
        const rawCat = document.getElementById("ar-cat").value;
        const category = rawCat === "__new__" ? (pendingCat || "Others") : rawCat;
        const moduleKey = document.getElementById("ar-mod").value.trim() || "Always";
        const bagLocation = document.getElementById("ar-bag").value;
        const scaleVal = document.getElementById("ar-scale-select").value;
        const scaleByDays = scaleVal !== "fixed";
        const ratioPerDay = scaleByDays ? (parseFloat(scaleVal) || 1.0) : 1.0;
        const existingRule =
          this.editingRuleIdx !== null ? this.state.custom_rules[this.editingRuleIdx] : null;
        const isDoorwayCritical =
          category === "On Me" ||
          category === "Docs" ||
          Boolean(existingRule?.isDoorwayCritical);
        const isHighLossReturn =
          existingRule && typeof existingRule.isHighLossReturn === "boolean"
            ? existingRule.isHighLossReturn
            : true;

        const updatedRule = {
          moduleKey,
          name,
          category,
          scaleByDays,
          ratioPerDay,
          isDoorwayCritical,
          isHighLossReturn,
          bagLocation,
        };

        if (this.editingRuleIdx !== null && this.state.custom_rules[this.editingRuleIdx]) {
          this.state.custom_rules[this.editingRuleIdx] = updatedRule;
        } else {
          this.state.custom_rules.push(updatedRule);
        }
        this.editingRuleIdx = null;
        this.closeModals();
        this.pushState();
      }

      deleteRule(idx, ev) {
        if (ev) ev.stopPropagation();
        const rule = this.state.custom_rules?.[idx];
        if (!rule) return;
        this.pendingDeleteRuleIdx = idx;
        document.getElementById("dr-title").textContent = \`Delete \${rule.name}?\`;
        document.getElementById("dr-desc").textContent = \`Are you sure you want to remove "\${rule.name}" from your Default Template?\`;
        document.getElementById("delete-rule-modal").classList.remove("hidden");
      }

      confirmDeleteRule() {
        if (this.pendingDeleteRuleIdx === null || this.pendingDeleteRuleIdx === undefined) return;
        this.state.custom_rules.splice(this.pendingDeleteRuleIdx, 1);
        this.pendingDeleteRuleIdx = null;
        this.closeModals();
        this.pushState();
      }

      render() {
        const isSigned = this.userId !== "default";
        const displayEmail = isSigned ? (this.state.email || this.userId) : "Local Profile (Not Signed In)";
        document.getElementById("drawer-user-dot").className = \`w-2 h-2 rounded-full \${isSigned ? "accent-bg" : "bg-slate-500"} shrink-0\`;
        document.getElementById("drawer-user-label").textContent = displayEmail;
        document.getElementById("drawer-sync-status").textContent = isSigned
          ? "Synced with Cloud Firestore"
          : "Local Mode · Sign in to sync";
        document.getElementById("drawer-trips-count").textContent = String((this.state.trips || []).length);
        document.getElementById("drawer-template-count").textContent = String((this.state.custom_rules || []).length);

        // Toggle Screens
        document.getElementById("screen-home").classList.toggle("hidden", this.currentScreen !== "home");
        document.getElementById("screen-trip-detail").classList.toggle("hidden", this.currentScreen !== "trip");
        document.getElementById("screen-template").classList.toggle("hidden", this.currentScreen !== "template");
        document.getElementById("screen-interview").classList.toggle("hidden", this.currentScreen !== "interview");
        document.getElementById("fab-btn").classList.toggle("hidden", this.currentScreen === "interview");

        // Header & FAB state
        const navSymbol = document.getElementById("nav-icon-symbol");
        const headerTitle = document.getElementById("header-title");
        const headerSubtitle = document.getElementById("header-subtitle");
        const fabLabel = document.getElementById("fab-label");

        if (this.currentScreen === "home") {
          navSymbol.textContent = "☰";
          headerTitle.textContent = "PackTrack";
          headerSubtitle.textContent = isSigned
            ? \`\${(this.state.trips || []).length} trip(s) · \${displayEmail}\`
            : \`\${(this.state.trips || []).length} trip(s)\`;
          fabLabel.textContent = "New Trip";
          this.renderHomeTripsList();
        } else if (this.currentScreen === "interview") {
          navSymbol.textContent = "←";
          const ivRange = this.formatDateRange(this.iv?.startDate, this.iv?.days, this.iv?.endDate);
          headerTitle.textContent = this.iv?.destination
            ? \`\${this.iv.destination} (\${ivRange} · \${this.iv.days}d)\`
            : "New Trip";
          headerSubtitle.textContent = this.iv?.destination
            ? \`\${ivRange} · \${this.iv.days} days · Interactive Gemini Trip Setup\`
            : "Interactive Gemini Trip Setup";
          this.renderInterviewScreen();
        } else if (this.currentScreen === "trip") {
          const trip = this.state.trips[this.activeTripIdx];
          if (!trip) {
            this.navigateHome();
            return;
          }
          const dateRange = this.formatDateRange(trip.startDate, trip.durationDays, trip.endDate);
          const plugPart = this.isPlugInfoNeeded(trip.country, trip.countryCode, trip.plugTypes)
            ? \` · Plug \${trip.plugTypes}\`
            : "";
          navSymbol.textContent = "←";
          headerTitle.textContent = \`\${trip.destination}, \${trip.country || ""}\`;
          headerSubtitle.textContent = \`\${dateRange} (\${trip.durationDays}d) · \${this.formatTempRange(trip.avgLowF, trip.avgHighF)}\${plugPart}\`;
          fabLabel.textContent = "Add Item";
          this.renderTripDetail(trip);
        } else if (this.currentScreen === "template") {
          navSymbol.textContent = "←";
          headerTitle.textContent = "Default Template";
          headerSubtitle.textContent = \`\${(this.state.custom_rules || []).length} items\`;
          fabLabel.textContent = "Add Item";
          this.renderTemplateRules();
        }
      }

      renderInterviewScreen() {
        const iv = this.iv || {
          step: "DESTINATION",
          destination: "",
          startDate: new Date().toISOString().slice(0, 10),
          endDate: new Date(Date.now() + 4 * 86400000).toISOString().slice(0, 10),
          days: 4,
        };
        const stepNumMap = {
          DESTINATION: 1,
          EXACT_DATES: 2,
          FLIGHT_OR_CAR: 3,
          RENTAL_CAR: 4,
          BAGS: 5,
          WORK_PURPOSE: 6,
          CREATING: 6,
        };
        const currentStepNum = stepNumMap[iv.step] || 1;
        const stepperEl = document.getElementById("iv-stepper");
        if (stepperEl) {
          stepperEl.innerHTML = Array.from({ length: 6 }, (_, i) => {
            const active = i + 1 <= currentStepNum;
            return \`<div class="h-1 rounded-full \${active ? "accent-bg" : "bg-[#1c2536]"}"></div>\`;
          }).join("");
        }

        const titleEl = document.getElementById("iv-title");
        const subEl = document.getElementById("iv-subtitle");
        const bodyEl = document.getElementById("iv-body");
        if (!titleEl || !subEl || !bodyEl) return;

        if (iv.step === "DESTINATION") {
          titleEl.textContent = "Where are you heading?";
          subEl.textContent = "Type your destination city below, or tap a city preset.";
          const presets = ["Seattle", "Atlanta", "Austin", "New York", "London", "Tokyo", "San Francisco", "Chicago"];
          bodyEl.innerHTML = \`
            <div class="flex gap-2">
              <input
                id="iv-dest-input"
                type="text"
                placeholder="e.g. Seattle, London, Tokyo..."
                value="\${iv.destination || ""}"
                onkeydown="if(event.key==='Enter'){event.preventDefault();app.answerInterviewDestination();}"
                class="flex-1 bg-[#131a26] text-white border border-[#232f42] rounded-xl px-3.5 py-2.5 text-sm focus:outline-none"
              />
              <button
                type="button"
                onclick="app.answerInterviewDestination()"
                class="accent-bg px-4 py-2.5 rounded-xl text-xs font-bold shrink-0"
              >
                Next →
              </button>
            </div>
            <div class="flex flex-wrap gap-2 pt-2">
              \${presets
                .map(
                  (c) => \`
                <button
                  type="button"
                  onclick="app.answerInterviewDestination('\${c}')"
                  class="surface-card hover:border-slate-500 px-3.5 py-2 rounded-full text-xs font-medium text-slate-200 transition"
                >
                  \${c}
                </button>\`
                )
                .join("")}
            </div>
          \`;
          setTimeout(() => document.getElementById("iv-dest-input")?.focus(), 40);
        } else if (iv.step === "EXACT_DATES") {
          titleEl.textContent = \`What are your exact travel dates for \${iv.destination}?\`;
          subEl.textContent = "Select your Start and End dates so we can fetch the weather forecast for your exact travel dates.";
          const todayIso = this.getLocalTodayIso();
          const datePresets = [
            { label: "Today → +3d", s: todayIso, e: this.addDaysIso(todayIso, 3) },
            { label: "Tomorrow → +4d", s: this.addDaysIso(todayIso, 1), e: this.addDaysIso(todayIso, 5) },
            { label: "In 3 days (4d)", s: this.addDaysIso(todayIso, 3), e: this.addDaysIso(todayIso, 7) },
            { label: "In 7 days (5d)", s: this.addDaysIso(todayIso, 7), e: this.addDaysIso(todayIso, 12) },
            { label: "In 2 weeks (5d)", s: this.addDaysIso(todayIso, 14), e: this.addDaysIso(todayIso, 19) },
            { label: "In 1 month (7d)", s: this.addDaysIso(todayIso, 30), e: this.addDaysIso(todayIso, 37) },
          ];
          const relLabel = this.formatRelativeStart(iv.startDate);
          bodyEl.innerHTML = \`
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              <div class="surface-card rounded-xl p-3.5 space-y-1.5">
                <label class="text-[10px] font-bold uppercase tracking-wider text-slate-400">Start Date (\${relLabel})</label>
                <input
                  id="iv-start-date"
                  type="date"
                  value="\${iv.startDate}"
                  onchange="app.onInterviewDateChange()"
                  class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-lg px-2.5 py-2 text-xs"
                />
              </div>
              <div class="surface-card rounded-xl p-3.5 space-y-1.5">
                <label class="text-[10px] font-bold uppercase tracking-wider text-slate-400">End Date (\${iv.days} days)</label>
                <input
                  id="iv-end-date"
                  type="date"
                  value="\${iv.endDate}"
                  min="\${iv.startDate}"
                  onchange="app.onInterviewDateChange()"
                  class="w-full bg-[#0b0f17] text-white border border-[#232f42] rounded-lg px-2.5 py-2 text-xs"
                />
              </div>
            </div>
            <div class="flex flex-wrap gap-2 pt-1">
              \${datePresets
                .map((p) => {
                  const sel = iv.startDate === p.s && iv.endDate === p.e;
                  return \`
                  <button
                    type="button"
                    onclick="app.setInterviewDatePreset('\${p.s}', '\${p.e}')"
                    class="px-3 py-1.5 rounded-full text-xs font-medium border transition \${
                      sel ? "accent-bg border-transparent font-bold" : "surface-card text-slate-300 hover:border-slate-500"
                    }"
                  >
                    \${p.label}
                  </button>\`;
                })
                .join("")}
            </div>
            <button
              type="button"
              onclick="app.confirmInterviewDates()"
              class="w-full accent-bg py-3 rounded-xl text-xs font-bold mt-2 hover:opacity-95 transition"
            >
              Continue (\${iv.startDate} → \${iv.endDate} · \${iv.days} days) →
            </button>
          \`;
        } else if (iv.step === "FLIGHT_OR_CAR") {
          titleEl.textContent = "Are you taking a flight or driving your own car?";
          subEl.textContent = "Personal car trips automatically include car essentials and skip bag limits.";
          bodyEl.innerHTML = \`
            <button type="button" onclick="app.answerInterviewTransport('flight')" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">Flight</p>
              <p class="text-xs text-slate-400">Flying to \${iv.destination} (includes flight items & luggage sizing)</p>
            </button>
            <button type="button" onclick="app.answerInterviewTransport('own_car')" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">Personal Car</p>
              <p class="text-xs text-slate-400">Driving own vehicle (storage space is not an issue)</p>
            </button>
          \`;
        } else if (iv.step === "RENTAL_CAR") {
          titleEl.textContent = "Do you plan to take a rental car?";
          subEl.textContent = "Includes rental car items if needed.";
          bodyEl.innerHTML = \`
            <button type="button" onclick="app.answerInterviewRental(true)" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">Yes — Rental Car</p>
              <p class="text-xs text-slate-400">Include items marked for rental car trips</p>
            </button>
            <button type="button" onclick="app.answerInterviewRental(false)" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">No — No Rental Car</p>
              <p class="text-xs text-slate-400">Skip rental car items</p>
            </button>
          \`;
        } else if (iv.step === "BAGS") {
          titleEl.textContent = "How many bags are you bringing?";
          subEl.textContent = "Carry-On is a standard US carry-on bag plus your personal shoulder bag.";
          bodyEl.innerHTML = \`
            <button type="button" onclick="app.answerInterviewBags('carry_on')" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">Carry-On + Shoulder Bag</p>
              <p class="text-xs text-slate-400">Standard carry-on suitcase + personal bag</p>
            </button>
            <button type="button" onclick="app.answerInterviewBags('checked_bag')" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">1 Check-In Bag + Carry-On</p>
              <p class="text-xs text-slate-400">1 checked suitcase + carry-on & personal bag</p>
            </button>
            <button type="button" onclick="app.answerInterviewBags('two_checked_bags')" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">2 Check-In Bags + Carry-On</p>
              <p class="text-xs text-slate-400">Full luggage capacity</p>
            </button>
            <button type="button" onclick="app.answerInterviewBags('personal_only')" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">Shoulder Bag Only</p>
              <p class="text-xs text-slate-400">Compact under-seat personal bag only</p>
            </button>
          \`;
        } else if (iv.step === "WORK_PURPOSE") {
          titleEl.textContent = "Do you plan to work on the trip?";
          subEl.textContent = "Includes work electronics & office items if working.";
          bodyEl.innerHTML = \`
            <button type="button" onclick="app.answerInterviewWork(true)" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">Yes — Work Trip</p>
              <p class="text-xs text-slate-400">Include work items from your template</p>
            </button>
            <button type="button" onclick="app.answerInterviewWork(false)" class="w-full text-left surface-card hover:border-slate-500 rounded-xl p-4 space-y-1 transition">
              <p class="text-sm font-semibold text-white">No — Personal / Leisure</p>
              <p class="text-xs text-slate-400">Skip work items</p>
            </button>
          \`;
        } else if (iv.step === "CREATING") {
          titleEl.textContent = \`Building your \${iv.destination} checklist...\`;
          subEl.textContent = \`Fetching weather for \${iv.startDate} → \${iv.endDate} and generating Gemini suggestions...\`;
          bodyEl.innerHTML = \`
            <div class="surface-card rounded-2xl p-8 text-center space-y-3">
              <div class="inline-block w-6 h-6 border-2 border-slate-500 border-t-white rounded-full animate-spin"></div>
              <p class="text-xs text-slate-300">Synthesizing template items & Gemini weather suggestions...</p>
            </div>
          \`;
        }
      }

      renderHomeTripsList() {
        const listEl = document.getElementById("trips-list");
        const emptyEl = document.getElementById("empty-trips");
        const trips = this.state.trips || [];

        listEl.innerHTML = "";
        emptyEl.classList.toggle("hidden", trips.length > 0);

        const indexedTrips = trips
          .map((trip, idx) => ({ trip, idx }))
          .sort(
            (a, b) =>
              this.sortKeyClosestToFarthest(a.trip.startDate) -
              this.sortKeyClosestToFarthest(b.trip.startDate)
          );

        indexedTrips.forEach(({ trip, idx }) => {
          const items = (trip.items || []).filter(
            (i) => !String(i.weatherReason || "").startsWith("[SUGGESTED] ")
          );
          const packed = items.filter((i) => Boolean(i.packedOutbound)).length;
          const total = items.length;
          const startLabel = this.formatRelativeStart(trip.startDate);
          const dateRange = this.formatDateRange(trip.startDate, trip.durationDays, trip.endDate);

          const card = document.createElement("div");
          card.onclick = () => this.openTripDetail(idx);
          card.className = "surface-card rounded-xl px-4 py-3.5 flex items-center justify-between gap-3 cursor-pointer hover:border-slate-500 transition";
          card.innerHTML = \`
            <div class="min-w-0 flex-1 space-y-1.5">
              <h3 class="text-sm font-semibold text-white truncate">\${trip.destination}</h3>
              <div class="flex items-center gap-1.5 flex-wrap">
                <span class="bg-[#1c2536] border border-[#232f42] text-slate-300 text-[11px] font-medium px-2 py-0.5 rounded-md shrink-0">\${startLabel}</span>
                <span class="accent-pill border text-[11px] font-semibold px-2 py-0.5 rounded-md shrink-0">\${dateRange} · \${trip.durationDays}d</span>
                <span class="text-xs text-slate-400 shrink-0">\${this.formatTempRange(trip.avgLowF, trip.avgHighF)}</span>
              </div>
            </div>
            <div class="flex items-center gap-2.5 shrink-0">
              <span class="text-xs font-medium text-slate-400">\${packed}/\${total}</span>
              <button
                type="button"
                title="Delete Trip"
                onclick="app.deleteTrip(\${idx}, event)"
                class="text-xs text-slate-500 hover:text-rose-400 px-1.5 py-0.5 rounded"
              >
                ✕
              </button>
            </div>
          \`;
          listEl.appendChild(card);
        });
      }

      renderTripDetail(trip) {
        const mode = trip.tripMode || "OUTBOUND";
        for (const m of ["OUTBOUND", "DOORWAY", "RETURN_SWEEP"]) {
          const el = document.getElementById(\`mode-\${m}\`);
          el.className = m === mode
            ? "py-1.5 rounded-lg text-xs font-semibold accent-bg transition"
            : "py-1.5 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition";
        }

        const indexedItems = (trip.items || []).map((item, idx) => ({ item, idx }));
        const activeIndexed = indexedItems.filter(
          ({ item }) => !String(item.weatherReason || "").startsWith("[SUGGESTED] ")
        );
        const suggestedIndexed = indexedItems.filter(({ item }) =>
          String(item.weatherReason || "").startsWith("[SUGGESTED] ")
        );
        const visible = activeIndexed.filter(({ item }) => {
          if (mode === "DOORWAY") return Boolean(item.isDoorwayCritical);
          if (mode === "RETURN_SWEEP") return Boolean(item.isHighLossReturn || item.packedOutbound);
          return true;
        });

        const doneCount = visible.filter(({ item }) =>
          mode === "RETURN_SWEEP" ? Boolean(item.packedReturn) : Boolean(item.packedOutbound)
        ).length;
        const pct = visible.length > 0 ? Math.round((doneCount / visible.length) * 100) : 0;

        document.getElementById("progress-label").textContent =
          mode === "DOORWAY" ? "60-Sec Doorway Check" : mode === "RETURN_SWEEP" ? "Hotel Checkout Sweep" : "Outbound Suitcase Progress";
        document.getElementById("progress-count").textContent = \`\${doneCount} / \${visible.length} (\${pct}%)\`;
        document.getElementById("progress-bar").style.width = \`\${pct}%\`;

        const fallbackBanner = document.getElementById("gemini-fallback-banner");
        if (fallbackBanner) {
          const isFallback =
            Boolean(trip.usedFallback) ||
            String(trip.lastAgentNote || "").includes("[FALLBACK]");
          fallbackBanner.classList.toggle("hidden", !isFallback);
        }

        const groupsEl = document.getElementById("checklist-groups");
        groupsEl.innerHTML = "";
        const byCat = new Map();
        for (const entry of visible) {
          const c = entry.item.category || "Basics (Leave-the-House)";
          if (!byCat.has(c)) byCat.set(c, []);
          byCat.get(c).push(entry);
        }

        const sortedCats = Array.from(byCat.keys()).sort((a, b) => {
          const ia = CATEGORY_ORDER.indexOf(a);
          const ib = CATEGORY_ORDER.indexOf(b);
          return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
        });

        for (const cat of sortedCats) {
          const entries = byCat.get(cat) || [];
          const catDone = entries.filter(({ item }) =>
            mode === "RETURN_SWEEP" ? Boolean(item.packedReturn) : Boolean(item.packedOutbound)
          ).length;

          const sec = document.createElement("div");
          sec.className = "space-y-2";
          sec.innerHTML = \`
            <div class="flex items-center justify-between px-1">
              <span class="text-xs font-bold uppercase tracking-wider text-slate-400">\${cat}</span>
              <span class="text-xs text-slate-500">\${catDone}/\${entries.length}</span>
            </div>
          \`;

          for (const { item, idx } of entries) {
            const checked = mode === "RETURN_SWEEP" ? Boolean(item.packedReturn) : Boolean(item.packedOutbound);
            const row = document.createElement("div");
            let pressTimer = null;
            let longPressed = false;
            const startPress = () => {
              longPressed = false;
              clearTimeout(pressTimer);
              pressTimer = setTimeout(() => {
                longPressed = true;
                this.openEditItemModal(idx);
              }, 450);
            };
            const cancelPress = () => {
              clearTimeout(pressTimer);
            };
            row.addEventListener("touchstart", startPress, { passive: true });
            row.addEventListener("touchend", cancelPress);
            row.addEventListener("touchmove", cancelPress);
            row.addEventListener("touchcancel", cancelPress);
            row.addEventListener("mousedown", (e) => {
              if (e.button === 0) startPress();
            });
            row.addEventListener("mouseup", cancelPress);
            row.addEventListener("mouseleave", cancelPress);
            row.oncontextmenu = (e) => {
              e.preventDefault();
              cancelPress();
              longPressed = true;
              this.openEditItemModal(idx);
            };
            row.onclick = () => {
              if (longPressed) {
                longPressed = false;
                return;
              }
              this.toggleItem(idx);
            };
            const removeTitle = mode === "DOORWAY" ? "Remove from Doorway Check (keeps in Pack)" : "Remove from Trip";
            row.className = \`surface-card rounded-xl px-3.5 py-2.5 flex items-center justify-between gap-3 cursor-pointer select-none hover:border-slate-600 transition \${checked ? "item-checked" : ""}\`;
            row.innerHTML = \`
              <div class="flex items-center gap-3 min-w-0">
                <div class="w-4 h-4 rounded flex items-center justify-center border \${checked ? "accent-bg border-transparent" : "border-slate-600"} text-[10px] font-bold shrink-0">
                  \${checked ? "✓" : ""}
                </div>
                <div class="min-w-0">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-medium text-white truncate \${checked ? "line-through" : ""}">\${item.name}</span>
                    \${item.quantity > 1 ? \`<span onclick="event.stopPropagation();app.openEditItemModal(\${idx})" class="accent-pill border text-[10px] font-bold px-1.5 py-0.2 rounded">×\${item.quantity}</span>\` : ""}
                  </div>
                  <p class="text-[11px] text-slate-400 truncate">
                    \${item.bagLocation || "Carry-On"}\${item.isDoorwayCritical ? " · Doorway" : ""}\${item.weatherReason ? " · " + item.weatherReason : ""}
                  </p>
                </div>
              </div>
              <div class="flex items-center gap-1 shrink-0">
                <button type="button" title="\${removeTitle}" onclick="app.deleteItem(\${idx}, event)" class="text-[11px] text-slate-500 hover:text-rose-400 px-1.5 py-1 rounded">✕</button>
              </div>
            \`;
            sec.appendChild(row);
          }
          groupsEl.appendChild(sec);
        }

        // Render Gemini Weather & Trip Suggestions at the end
        if (suggestedIndexed.length > 0) {
          const sugSec = document.createElement("div");
          sugSec.className = "space-y-2 pt-3";
          sugSec.innerHTML = \`
            <div class="px-1 space-y-0.5">
              <div class="flex items-center justify-between">
                <span class="text-xs font-bold uppercase tracking-wider accent-text">✨ Gemini Weather & Trip Suggestions</span>
                <span class="text-xs text-slate-500">\${suggestedIndexed.length}</span>
              </div>
              <p class="text-[11px] text-slate-400">Suggested based on forecast & trip details — tap + Add to include in your checklist</p>
            </div>
          \`;

          for (const { item, idx } of suggestedIndexed) {
            const cleanReason = String(item.weatherReason || "").replace(/^\[SUGGESTED\]\s*/i, "").trim();
            const card = document.createElement("div");
            card.className = "surface-subtle rounded-xl px-3.5 py-3 flex items-center justify-between gap-3 border accent-pill";
            card.style.backgroundColor = "rgba(28, 37, 54, 0.85)";
            card.innerHTML = \`
              <div class="min-w-0 space-y-1">
                <div class="flex items-center gap-2 flex-wrap">
                  <span class="text-xs font-semibold text-white">\${item.name}</span>
                  <span class="bg-[#0b0f17] text-slate-300 border border-[#232f42] text-[10px] font-medium px-2 py-0.5 rounded-md">
                    \${item.category || "Others"} · \${item.bagLocation || "Carry-On"}
                  </span>
                </div>
                \${cleanReason ? \`<p class="text-[11px] text-slate-300 leading-relaxed">\${cleanReason}</p>\` : ""}
              </div>
              <div class="flex items-center gap-1.5 shrink-0">
                <button
                  type="button"
                  onclick="app.acceptSuggestedItem(\${idx}, event)"
                  class="accent-bg px-2.5 py-1.5 rounded-lg text-[11px] font-bold hover:opacity-95 transition"
                >
                  + Add
                </button>
                <button
                  type="button"
                  title="Dismiss Suggestion"
                  onclick="app.deleteItem(\${idx}, event)"
                  class="bg-[#0b0f17] text-slate-400 hover:text-rose-400 border border-[#232f42] px-2 py-1.5 rounded-lg text-[11px]"
                >
                  ✕
                </button>
              </div>
            \`;
            sugSec.appendChild(card);
          }
          groupsEl.appendChild(sugSec);
        }
      }

      renderTemplateRules() {
        const condBtn = document.getElementById("tpl-group-cond");
        const catBtn = document.getElementById("tpl-group-cat");
        const byCondition = this.templateGrouping !== "category";
        if (condBtn && catBtn) {
          condBtn.className = byCondition
            ? "py-1.5 rounded-lg text-xs font-semibold accent-bg transition"
            : "py-1.5 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition";
          catBtn.className = !byCondition
            ? "py-1.5 rounded-lg text-xs font-semibold accent-bg transition"
            : "py-1.5 rounded-lg text-xs font-medium text-slate-400 hover:text-white transition";
        }

        const tempGroupsEl = document.getElementById("template-groups");
        tempGroupsEl.innerHTML = "";
        const groups = new Map();
        (this.state.custom_rules || []).forEach((rule, idx) => {
          const condLabel = formatCondition(rule.moduleKey);
          const key = byCondition ? condLabel : (rule.category || "Others");
          if (!groups.has(key)) groups.set(key, []);
          groups.get(key).push({ rule, idx, condLabel });
        });

        const sortedKeys = Array.from(groups.keys()).sort((a, b) => {
          const orderArr = byCondition ? CONDITION_ORDER : CATEGORY_ORDER;
          const ia = orderArr.indexOf(a);
          const ib = orderArr.indexOf(b);
          return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
        });

        for (const groupKey of sortedKeys) {
          const entries = groups.get(groupKey) || [];
          const headerLabel = groupKey;
          const sec = document.createElement("div");
          sec.className = "space-y-2";
          sec.innerHTML = \`
            <div class="flex items-center justify-between px-1">
              <span class="text-xs font-bold uppercase tracking-wider \${byCondition ? "accent-text" : "text-slate-400"}">\${headerLabel}</span>
              <span class="text-xs text-slate-500">\${entries.length}</span>
            </div>
          \`;
          for (const { rule, idx, condLabel } of entries) {
            const subtitlePrefix = byCondition ? (rule.category || "Others") : condLabel;
            const rawBag = String(rule.bagLocation || "Carry-On").toLowerCase();
            const bagLabel = rawBag.includes("check")
              ? "Check-In"
              : rawBag.includes("carry")
              ? "Carry-On"
              : "Personal";
            const rateNum = Number(rule.ratioPerDay) || 1;
            const rateLabel = \`\${rateNum}/day\`;
            const card = document.createElement("div");
            card.onclick = () => this.openEditRuleModal(idx);
            card.className = "surface-card rounded-xl px-3.5 py-2.5 flex items-center justify-between gap-3 cursor-pointer hover:border-slate-500 transition";
            card.innerHTML = \`
              <div>
                <p class="text-xs font-medium text-white">\${rule.name}</p>
                <p class="text-[11px] text-slate-400">
                  \${subtitlePrefix} · \${bagLabel}\${rule.scaleByDays ? " · " + rateLabel : ""}
                </p>
              </div>
              <button type="button" onclick="app.deleteRule(\${idx}, event)" class="text-xs text-slate-500 hover:text-rose-400 px-2 py-1">✕</button>
            \`;
            sec.appendChild(card);
          }
          tempGroupsEl.appendChild(sec);
        }
      }
    }

    window.app = new PackTrackClient();
  </script>
</body>
</html>`;
