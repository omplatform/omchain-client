# omchain-client — คำสั่งประจำวันของ fork นี้
#
# Besu ใช้ Gradle ซึ่งคำสั่งยาวและต้องจำ flag เยอะ ไฟล์นี้ห่อไว้ให้สั้น
# ไม่มีอะไรวิเศษ ทุกเป้าหมายคือคำสั่งที่เราพิมพ์มือกันอยู่แล้ว
#
#   make help          ดูคำสั่งทั้งหมด
#
# ต้องรันบนเครื่องที่มี docker (trx40) — ตัว compile ก็วิ่งใน container ไม่ต้องลง JDK

SHELL := /bin/bash
.DEFAULT_GOAL := help

# ── ปรับได้จาก command line เช่น  make image TAG=26.8.1 ──────────────────────
BASE        ?= 26.8.0
TAG         ?= $(BASE)
IMAGE       ?= omchain-client
VERSION     ?= $(BASE)-omchain
# ตั้งเองตอนใช้:  make push TAG=... REGISTRY=<host>/<path>
REGISTRY    ?=
STACK       ?= /opt/stacks/omchain-qbft-trial
PORT        ?= 49544
# สคริปต์ตรวจบางตัวต้องเซ็น tx จึงต้องใช้ python ที่มี eth-account
PY          ?= /home/ubuntu/venv-t092/bin/python
JDK         ?= eclipse-temurin:25-jdk
GRADLE      := docker run --rm -v $(PWD):/src -w /src \
                 -v omchain-gradle-cache:/root/.gradle \
                 --memory 28g --cpus 20 $(JDK) ./gradlew --no-daemon

# พอร์ต RPC ของเชนทดลอง เรียงตามลำดับที่ปลอดภัยเวลา rolling (ไม่ใช่ validator ก่อน)
NODES       := qrpc:49544 qc:49543 qb:49542 qa:49541

.PHONY: help build image scan test test-all verify status deploy push upgrade clean version

help: ## ดูคำสั่งทั้งหมด
	@echo "omchain-client — fork ของ Besu ที่รัน omchain (chainId 1246)"
	@echo
	@grep -E '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'
	@echo
	@echo "  ตัวแปรที่ปรับได้:  TAG=$(TAG)  BASE=$(BASE)  PORT=$(PORT)"

version: ## ดูว่า patch ของเรามีอะไรบ้าง เทียบกับต้นทาง
	@echo "  ฐาน upstream : $(BASE)"
	@echo "  patch ของเรา :"
	@git log --oneline $(BASE)..HEAD | sed 's/^/    /'
	@echo "  รวม $$(git rev-list --count $(BASE)..HEAD) commit"

build: ## คอมไพล์ (ผลลัพธ์อยู่ build/install/besu)
	$(GRADLE) installDist
	@sudo chown -R $$(id -u):$$(id -g) build .gradle 2>/dev/null || true

image: build ## ประกอบ docker image  (make image TAG=26.8.1)
	@rm -rf /tmp/omchain-img && mkdir -p /tmp/omchain-img
	@cp -r build/install/besu /tmp/omchain-img/besu
	@cp docker/Dockerfile docker/pyroscope.properties /tmp/omchain-img/
	cd /tmp/omchain-img && sudo docker build --build-arg VERSION=$(VERSION) \
	  -t $(IMAGE):$(TAG) . | tail -2
	@rm -rf /tmp/omchain-img
	@echo "  --- ตรวจว่าเป็นตัวเรา ---"
	@sudo docker run --rm $(IMAGE):$(TAG) --version | sed 's/^/  /'
	@sudo docker run --rm $(IMAGE):$(TAG) --help \
	  | grep -oE '\-\-(tx-pool-(max-future-age-seconds|reserve-sender-balance)|rpc-(debug-read-only|geth-compatible-errors))' \
	  | sort -u | sed 's/^/  มี flag /'

scan: ## สแกนหาความลับ/ชื่อ infra ภายใน ในของที่เราเขียนเอง
	@bad=0; \
	pats='AUTH_SECRET *= *[^$$]|KEY_STORE_SECRET|X-API-KEY" *: *"[^$$o]|-p@|-----BEGIN [A-Z ]*PRIVATE KEY|[a-z0-9-]+\.leafbot\.io|[a-z0-9.-]+\.bytepluses\.com|10\.8\.0\.[0-9]'; \
	hits=$$(git grep -nIE "$$pats" -- omchain/ 2>/dev/null | grep -v 'os\.environ' || true); \
	if [ -n "$$hits" ]; then bad=1; echo "  ❌ ในไฟล์ omchain/"; echo "$$hits" | sed 's/^/      /'; fi; \
	added=$$(git diff $(BASE_TAG)..HEAD -- ':!*.md' 2>/dev/null | grep '^+' | grep -vE '^\+\+\+' \
	  | grep -E "$$pats" | grep -v 'os\.environ' || true); \
	if [ -n "$$added" ]; then bad=1; echo "  ❌ ในบรรทัดที่เราเพิ่มเทียบ upstream"; echo "$$added" | sed 's/^/      /'; fi; \
	if [ $$bad -eq 0 ]; then echo "  ✅ ไม่พบความลับหรือชื่อ infra ภายในในของที่เราเขียน"; else \
	  echo; echo "  ห้าม commit จนกว่าจะย้ายค่าพวกนี้ไปอยู่ใน environment"; exit 1; fi

test: scan ## ชุดทดสอบที่เกี่ยวกับ patch ของเรา (เร็ว) + สแกนความลับ
	$(GRADLE) spotlessJavaCheck \
	  :ethereum:eth:test --tests '*transactions*' --tests '*ReserveSenderBalance*' \
	  :ethereum:api:test --tests '*GethCompatible*' --tests '*DebugJsonRpc*' \
	  :consensus:qbft-core:test \
	  :app:test --tests '*TransactionPoolOptions*' --tests '*BesuCommandTest*'

test-all: ## ชุดทดสอบเต็มของโมดูลที่เราแตะ (ช้า ใช้ก่อนออก tag)
	$(GRADLE) spotlessJavaCheck :ethereum:eth:test :ethereum:api:test :consensus:qbft-core:test

deploy: ## เอา image ขึ้นเชนทดลองทีละ node  (make deploy TAG=26.8.1)
	@sudo sed -i 's|^BESU_IMAGE=.*|BESU_IMAGE=$(IMAGE):$(TAG)|' $(STACK)/.env
	@for n in $(NODES); do \
	  name=$${n%%:*}; port=$${n##*:}; \
	  echo "  → $$name"; \
	  (cd $(STACK) && sudo docker compose up -d --no-deps --force-recreate $$name >/dev/null); \
	  for i in $$(seq 1 60); do sleep 2; \
	    curl -s -m 3 -X POST -H 'Content-Type: application/json' \
	      --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
	      http://127.0.0.1:$$port >/dev/null 2>&1 && break; done; \
	  sleep 15; \
	done
	@$(MAKE) --no-print-directory status

status: ## ดูหัวเชนกับจำนวน peer ของทุก node
	@for n in $(NODES); do \
	  name=$${n%%:*}; port=$${n##*:}; \
	  head=$$(curl -s -m 5 -X POST -H 'Content-Type: application/json' \
	    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
	    http://127.0.0.1:$$port | python3 -c 'import sys,json;print(int(json.load(sys.stdin)["result"],16))' 2>/dev/null || echo down); \
	  peers=$$(curl -s -m 5 -X POST -H 'Content-Type: application/json' \
	    --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
	    http://127.0.0.1:$$port | python3 -c 'import sys,json;print(int(json.load(sys.stdin)["result"],16))' 2>/dev/null || echo '-'); \
	  printf "  %-6s head=%-12s peers=%s\n" $$name $$head $$peers; \
	done

verify: ## ตรวจว่า feature ของเราทำงานจริงบนเชนที่รันอยู่ (make verify PORT=49544)
	@echo "  --- namespace DEBUG เหลือแต่คำสั่งอ่าน ---"
	@python3 omchain/verify/debug-readonly-check.py $(PORT) | tail -14
	@echo
	@echo "  --- error ตรงกับ geth ไหม (เปิด geth ชั่วคราวเทียบให้ดู) ---"
	@sudo $(PY) omchain/verify/geth-vs-besu-errors.py | tail -10

push: ## ดัน image ขึ้น registry  (make push TAG=26.8.1 REGISTRY=<host>/<path>)
	@test -n "$(REGISTRY)" || { echo "  ต้องระบุ REGISTRY เช่น  make push REGISTRY=registry.example/omchain"; exit 1; }
	sudo docker tag $(IMAGE):$(TAG) $(REGISTRY)/$(IMAGE):$(TAG)
	sudo docker push $(REGISTRY)/$(IMAGE):$(TAG)

upgrade: ## ยก patch ขึ้น upstream รุ่นใหม่  (make upgrade V=26.8.1)
	@test -n "$(V)" || { echo "  ต้องระบุ V เช่น  make upgrade V=26.8.1"; exit 1; }
	git fetch upstream --tags
	@echo "  --- รุ่นใหม่แตะไฟล์ที่เราแก้ไหม ---"
	@git diff --name-only $(BASE)..$(V) | grep -E \
	  'TransactionPool|SparseTransactions|QbftBlockHeightManager|EthSendRawTransaction|DebugJsonRpcMethods|ApiConfiguration|CliqueProtocolSchedule' \
	  | sed 's/^/    /' || echo "    ไม่แตะเลย"
	@echo "  --- security fix ในรุ่นใหม่ ---"
	@git log --oneline $(BASE)..$(V) | grep -iE 'GHSA|CVE|security' | sed 's/^/    /' || echo "    ไม่มี"
	@echo
	@echo "  ต่อไปทำเอง (ต้องแก้ conflict ด้วยมือ):"
	@echo "    git checkout main && git merge --ff-only upstream/main"
	@echo "    git checkout omclient && git rebase $(V)"
	@echo "    make test-all && make image TAG=$(V) BASE=$(V)"
	@echo "    git tag omchain-v$(V) && git push origin omclient --force-with-lease"

clean: ## ล้างของที่ build ไว้
	@sudo rm -rf build */build */*/build /tmp/omchain-img 2>/dev/null || true
	@echo "  ล้างแล้ว"
