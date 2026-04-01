# OpenPod development shortcuts
# Usage: make <target>

PACKAGE    := com.openpod
ACTIVITY   := $(PACKAGE)/.MainActivity
GRADLE     := ./gradlew
ADB        := adb
EMULATOR   := emulator/run.py
VENV       := emulator/.venv/bin/activate

.PHONY: help install install-emu run reset clear test test-emu build \
        emulator emulator-seed lint

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ── Build & install ─────────────────────────────────────────────────

build: ## Build debug APK
	$(GRADLE) assembleDebug

install: ## Install debug APK (fake pod manager)
	$(GRADLE) :app:installDebug

install-emu: ## Install debug APK with emulator mode
	$(GRADLE) :app:installDebug -PuseEmulator=true

# ── Device control ──────────────────────────────────────────────────

run: ## Launch the app on device
	$(ADB) shell am start -n $(ACTIVITY)

reset: ## Clear all app data and relaunch
	$(ADB) shell pm clear $(PACKAGE)
	$(ADB) shell am start -n $(ACTIVITY)

clear: ## Clear all app data (no relaunch)
	$(ADB) shell pm clear $(PACKAGE)

reinstall: install-emu reset ## Install emulator build, reset, and launch

# ── Emulator (Python) ──────────────────────────────────────────────

emulator: ## Start the pod emulator (TCP mode)
	cd emulator && source $(VENV) && python $(EMULATOR) --mode tcp

emulator-seed: ## Start the pod emulator (deterministic)
	cd emulator && source $(VENV) && python $(EMULATOR) --mode tcp --seed 42

emulator-debug: ## Start the pod emulator with debug logging
	cd emulator && source $(VENV) && python $(EMULATOR) --mode tcp --log-level DEBUG

# ── Tests ───────────────────────────────────────────────────────────

test: ## Run Android unit tests
	$(GRADLE) testDebugUnitTest

test-emu: ## Run all Python emulator tests
	cd emulator && source $(VENV) && python -m pytest tests/ -v

# ── Native crypto vectors ───────────────────────────────────────────

vectors: ## Extract reference vectors from native .so via Docker/QEMU
	docker build -t openpod-native-vectors tests/native_vectors/
	docker run --rm openpod-native-vectors > tests/native_vectors/vectors.json
	@echo "Vectors saved to tests/native_vectors/vectors.json"

# ── Quality ─────────────────────────────────────────────────────────

lint: ## Run Android lint
	$(GRADLE) lintDebug
