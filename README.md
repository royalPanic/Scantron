# Scantron Container Inventory

**Scantron Container Inventory** is a modern Android application designed for physical container tracking, warehouse inventory management, and cross-container item search. The app is optimized for both consumer smartphones and enterprise handheld mobile computers (such as the **Honeywell Mobility CK65** and Zebra TC-series scanners).

---

## 🌟 Key Features

### 🏷️ Physical Container Lookup
* **Instant Tag Search**: Dedicated scanner page for scanning or typing physical container tag IDs (e.g., `BOX-101`, `BIN-05`, `SHELF-A`).
* **Hardware Barcode Intercept**: Intercepts `Key.Enter` / `Key.Tab` events sent by hardware 2D barcode scanner engines (Honeywell DataCollection / Virtual Wedge) for instant zero-tap container opening.
* **Auto-Creation**: Typing or scanning a new container tag automatically provisions a container shell ready for item entry.

### 📦 Inventory & Item Management
* **Container Details**: View and manage container metadata including Tag ID, Friendly Name (e.g. *Garage Power Tools*), Location (e.g. *Shelf 2-A*), and Notes.
* **Item Tracking**: Add, edit, and remove container contents with item names, optional **barcodes/SKUs**, stock quantities, categories, and descriptive notes.
* **In-Place Quantity Adjustments**: Adjust item stock quantities directly on each card using `-` and `+` controls.

### 🔍 Global Cross-Container Search
* **Search Everything**: Search stored inventory across all containers by item name, category, notes, or scanned barcode/SKU.
* **Container Location Badges**: Search results highlight the exact physical container tag ID and location where each item is stored.
* **Direct Jump**: Tapping any search result navigates directly to that container's full inventory.

### 🏭 Enterprise & Handheld Mobile Computer Optimizations
* **Optimized for Honeywell CK65**: Tailored for 4.0-inch WVGA (480 × 800) compact displays and physical 51-key / 38-key keypads.
* **Compact High-Density UI**: Reduced padding and dense card layouts fit 4–5 containers/items on small screens simultaneously without excessive scrolling.
* **Industrial Ergonomics**: Touch targets adhere to 48dp+ accessibility standards for easy single-handed thumb tapping or gloved-hand operation.
* **Bottom-Anchored FAB**: The `+ New Container` button is anchored at the bottom right above the bottom navigation bar for comfortable thumb reach.

---

## 📱 Tech Stack & Architecture

* **Language**: Kotlin 2.0.21
* **UI Toolkit**: Jetpack Compose with Material 3 Design
* **Database**: Room 2.6.1 with KSP (`com.google.devtools.ksp`) annotation processing
* **Database Migration**: Includes SQLite `MIGRATION_1_2` for adding item barcode tracking
* **Navigation**: Jetpack Navigation Compose
* **Platform Target**: Android 13 (API Level 33) with backward compatibility to Android 7.0 (API 24)
* **Edge-to-Edge & Insets**: Native `enableEdgeToEdge()` and `imePadding()` support for soft keyboard resizing without UI overlap
* **Build System**: Gradle 9.0 (Kotlin DSL)

---

## 🗄️ Database Schema

### `containers` Table
| Column | Type | Description |
| :--- | :--- | :--- |
| `id` (PK) | `TEXT` | Physical container tag/code (e.g., `BOX-101`) |
| `name` | `TEXT` | Optional friendly container label |
| `location` | `TEXT` | Physical location (e.g., `Shelf 2-A`) |
| `notes` | `TEXT` | Additional details |
| `updatedAt` | `INTEGER` | Epoch timestamp |

### `container_items` Table
| Column | Type | Description |
| :--- | :--- | :--- |
| `id` (PK) | `INTEGER` | Auto-generated ID |
| `containerId` (FK) | `TEXT` | Foreign key referencing `containers(id)` |
| `name` | `TEXT` | Item name |
| `barcode` | `TEXT` | Optional item barcode, SKU, or UPC |
| `quantity` | `INTEGER` | In-stock quantity |
| `category` | `TEXT` | Category tag (e.g., *Tools*, *Electronics*) |
| `notes` | `TEXT` | Item description or serial numbers |
| `updatedAt` | `INTEGER` | Epoch timestamp |

---

## 🛠️ Building & Running

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-repo/scantron.git
   cd scantron
   ```

2. **Open in Android Studio**:
   Open the project directory in Android Studio (Ladybug / 2024.2+ or Android Studio Jellyfish/Koala).

3. **Build via Gradle**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Deploy to Device**:
   Deploy to any Android device running Android 7.0+ or enterprise handheld mobile computers like the **Honeywell CK65** or **Zebra TC52**.

---

## 📄 License
This project is licensed under the MIT License.
