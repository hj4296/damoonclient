-- Lune 테스트: 빌드한 플레이스 파일을 열어 모듈을 실제 Roblox 인스턴스 데이터로 실행해 봅니다.
-- 사용법: lune run tests/run.luau BlockArena.rbxlx
local fs = require("@lune/fs")
local luau = require("@lune/luau")
local process = require("@lune/process")
local roblox = require("@lune/roblox")

local placePath = process.args[1] or "BlockArena.rbxlx"
local game = roblox.deserializePlace(fs.readFile(placePath))
local workspace = game:GetService("Workspace")

local passed, failed = 0, 0
local function check(cond: any, message: string)
	if cond then
		passed += 1
	else
		failed += 1
		print("  실패: " .. message)
	end
end
local function section(name: string)
	print("== " .. name)
end

-- 간단한 Random 대용 (Lune 에는 Roblox Random 이 없음)
local FakeRandom = {}
FakeRandom.__index = FakeRandom
function FakeRandom.new(seed: number?)
	return setmetatable({ state = seed or 12345 }, FakeRandom)
end
function FakeRandom:NextNumber()
	self.state = (self.state * 48271) % 2147483647
	return self.state / 2147483647
end

local cache = {}
local function makeEnv(module)
	local env = {
		script = module,
		game = game,
		workspace = workspace,
		Instance = roblox.Instance,
		Vector3 = roblox.Vector3,
		Vector2 = roblox.Vector2,
		CFrame = roblox.CFrame,
		Color3 = roblox.Color3,
		UDim = roblox.UDim,
		UDim2 = roblox.UDim2,
		Enum = roblox.Enum,
		NumberRange = roblox.NumberRange,
		NumberSequence = roblox.NumberSequence,
		ColorSequence = roblox.ColorSequence,
		Random = FakeRandom,
		warn = print,
	}
	return env
end

local function requireModule(module)
	if cache[module] ~= nil then
		return cache[module]
	end
	assert(module and module.ClassName == "ModuleScript", "ModuleScript 가 아님: " .. tostring(module))
	local env = makeEnv(module)
	env.require = requireModule
	local fn = luau.load(module.Source, { debugName = module:GetFullName(), environment = env })
	local result = fn()
	cache[module] = result
	return result
end

local Shared = game:GetService("ReplicatedStorage"):FindFirstChild("Shared")
local Server = game:GetService("ServerScriptService"):FindFirstChild("Server")

section("플레이스 구조")
local starterPlayer = game:GetService("StarterPlayer")
local playerScripts = starterPlayer:FindFirstChild("StarterPlayerScripts")
local characterScripts = starterPlayer:FindFirstChild("StarterCharacterScripts")
check(Shared ~= nil, "ReplicatedStorage.Shared 있음")
check(Server ~= nil and Server:FindFirstChild("Main") ~= nil and Server.Main.ClassName == "Script", "서버 Main 스크립트")
local client = playerScripts and playerScripts:FindFirstChild("Client")
check(client ~= nil and client:FindFirstChild("Main") ~= nil and client.Main.ClassName == "LocalScript", "클라이언트 Main 로컬스크립트")
check(characterScripts ~= nil and characterScripts:FindFirstChild("Health") ~= nil, "기본 회복을 끄는 Health 스크립트")
check(game:GetService("Players").CharacterAutoLoads == false, "CharacterAutoLoads 꺼짐")
check(math.abs(workspace.Gravity - 130) < 0.01, "중력 130")

section("모든 스크립트 문법")
local scriptCount = 0
for _, item in game:GetDescendants() do
	if item:IsA("LuaSourceContainer") then
		scriptCount += 1
		local ok, err = pcall(luau.compile, item.Source)
		check(ok, item:GetFullName() .. " 컴파일: " .. tostring(err))
	end
end
check(scriptCount >= 40, "스크립트 수 " .. scriptCount)

-- require(script.Parent.X) 로 가리키는 모듈이 실제로 있는지 확인
section("require 경로")
for _, item in game:GetDescendants() do
	if item:IsA("LuaSourceContainer") then
		for path in string.gmatch(item.Source, "require%(([%w%.]+)%)") do
			local node = nil
			local parts = string.split(path, ".")
			if parts[1] == "script" then
				node = item
				for i = 2, #parts do
					if node == nil then
						break
					end
					if parts[i] == "Parent" then
						node = node.Parent
					else
						node = node:FindFirstChild(parts[i])
					end
				end
				check(node ~= nil, item:GetFullName() .. " -> " .. path)
			elseif parts[1] == "Shared" or parts[1] == "Server" then
				node = if parts[1] == "Shared" then Shared else Server
				for i = 2, #parts do
					if node == nil then
						break
					end
					if parts[i] == "Parent" then
						node = node.Parent
					else
						node = node:FindFirstChild(parts[i])
					end
				end
				check(node ~= nil, item:GetFullName() .. " -> " .. path)
			end
		end
	end
end

local Config = requireModule(Shared.Config)
local Blocks = requireModule(Shared.Blocks)
local Items = requireModule(Shared.Items)
local Rules = requireModule(Shared.Rules)
local Shop = requireModule(Shared.Shop)
local Icons = requireModule(Shared.Icons)
local Util = requireModule(Shared.Util)

section("아이콘")
for name, art in Icons.Art do
	check(#art == Icons.Size, name .. " 줄 수")
	for row, line in art do
		check(#line == Icons.Size, string.format("%s %d번째 줄 길이 %d", name, row, #line))
		for i = 1, #line do
			local ch = string.sub(line, i, i)
			check(ch == "." or ch == "M" or ch == "L" or ch == "D" or Icons.Fixed[ch] ~= nil, name .. " 알 수 없는 글자 " .. ch)
		end
	end
end

section("아이템/블록/상점")
for id, item in Items.defs do
	check(Icons.Art[item.icon] ~= nil, id .. " 아이콘 " .. tostring(item.icon))
	check(item.stack >= 1, id .. " 묶음 수")
end
for id, block in Blocks.defs do
	check(typeof(block.color) == "Color3", id .. " 색")
	check(typeof(block.material) == "EnumItem", id .. " 재질")
end
for _, team in Config.Teams do
	for _, category in Shop.Categories do
		check(Items.get(Shop.resolve(category.icon, team.id)) ~= nil, "상점 탭 아이콘 " .. category.icon)
		for _, item in category.items do
			if item.give then
				check(Items.get(Shop.resolve(item.give, team.id)) ~= nil, "상점 " .. item.key .. " -> " .. item.give)
			end
			if item.icon then
				check(Items.get(Shop.resolve(item.icon, team.id)) ~= nil, "상점 아이콘 " .. item.icon)
			end
			for _, tier in item.tiers or {} do
				check(Items.get(tier.give) ~= nil, "상점 단계 " .. tier.give)
				check(Items.get(tier.cost.item) ~= nil, "가격 아이템 " .. tier.cost.item)
			end
			if item.cost then
				check(Items.get(item.cost.item) ~= nil and Items.get(item.cost.item).kind == "currency", "가격 아이템 " .. item.cost.item)
			end
			if item.armor then
				for _, piece in { "leggings", "boots" } do
					check(Items.get(item.armor .. "_" .. piece) ~= nil, "갑옷 " .. item.armor .. "_" .. piece)
				end
			end
		end
	end
end
for _, upgrade in Shop.Upgrades do
	check(Items.get(upgrade.icon) ~= nil, "강화 아이콘 " .. upgrade.icon)
	check(#upgrade.levels == #upgrade.costs, upgrade.key .. " 단계 설명 수 = 가격 수")
end
local upgradeKeys = {}
for _, upgrade in Shop.Upgrades do
	upgradeKeys[upgrade.key] = true
end
for _, key in { "sharpness", "protection", "haste", "forge", "healpool", "dragon" } do
	check(upgradeKeys[key] == true, "팀 강화 " .. key)
end
check(#Shop.getUpgrade("forge").costs == 4, "강화 용광로 4단계")
check(#Shop.getUpgrade("protection").costs == 4, "강화 갑옷 4단계")
for _, trap in Shop.Traps do
	check(Items.get(trap.icon) ~= nil, "함정 아이콘 " .. trap.icon)
	check(Shop.getTrap(trap.key) == trap, "함정 찾기 " .. trap.key)
end
check(#Shop.Traps == 4, "함정 4종")
check(Shop.trapCost(0) == 1 and Shop.trapCost(1) == 2 and Shop.trapCost(2) == 4 and Shop.trapCost(3) == nil, "함정 가격 1 -> 2 -> 4")
local quick = Shop.Categories[1]
check(quick.quick == true and #quick.items == #Shop.QuickBuy, "빠른 구매 탭")
check(#quick.items <= 18, "빠른 구매는 두 줄(18칸) 안")
for _, category in Shop.Categories do
	check(#category.items <= 18, category.id .. " 물건이 두 줄(18칸) 안")
end
check(#Shop.Categories <= 9, "상점 탭 9개 이하")
local shopKeys = {}
for _, category in Shop.Categories do
	for _, item in category.items do
		shopKeys[item.key] = true
	end
end
for _, key in { "ladder", "hardstone", "water_bucket", "sponge", "bridge_egg", "popup_tower", "kb_stick", "bow_power", "bow_punch", "speed_potion", "jump_potion", "invis_potion", "magic_milk", "golem_egg", "bed_bug" } do
	check(shopKeys[key] == true, "상점에 " .. key)
end

-- 아이템 종류별 필수 값
local EFFECTS = Config.Effects
for id, item in Items.defs do
	if item.kind == "potion" then
		check(item.effect == "heal" or EFFECTS[item.effect] ~= nil, id .. " 물약 효과 " .. tostring(item.effect))
	elseif item.kind == "bucket" then
		local block = Blocks.get(item.liquid or "")
		check(block ~= nil and (block.water == true or block.burn == true), id .. " 양동이 액체")
	elseif item.kind == "deploy" then
		check(item.deploy == "tower" or item.deploy == "golem", id .. " 설치 종류")
	elseif item.kind == "throwable" then
		local known = { pearl = true, fireball = true, snowball = true, bridge_egg = true, bed_bug = true }
		check(known[item.projectile or ""] == true, id .. " 던지기 종류")
	end
end
for kind, mob in Config.Mobs do
	check(mob.health > 0 and mob.damage > 0 and mob.speed > 0 and mob.attackInterval > 0, kind .. " 몹 수치")
end

local Kits = requireModule(Shared.Kits)
check(Kits.get(Kits.DefaultKit) ~= nil and Kits.getPerk(Kits.DefaultPerk) ~= nil, "기본 키트/능력")
local kitIds = {}
for _, kit in Kits.List do
	check(not kitIds[kit.id], "키트 id 겹침 " .. kit.id)
	kitIds[kit.id] = true
	check(Items.get(kit.icon) ~= nil, "키트 아이콘 " .. kit.icon)
	check(#kit.items > 0, kit.id .. " 키트 아이템")
	for _, stack in kit.items do
		local def = Items.get(stack.id)
		check(def ~= nil and stack.count >= 1 and stack.count <= def.stack, kit.id .. " 키트 아이템 " .. stack.id)
	end
end
check(#Kits.List <= 12, "키트 창 두 줄(12칸) 안")
for _, perk in Kits.Perks do
	check(Items.get(perk.icon) ~= nil, "능력 아이콘 " .. perk.icon)
end
check(#Kits.Perks <= 6, "능력 한 줄(6칸) 안")

section("계산식")
check(Rules.breakTime("wool_red", "shears") < Rules.breakTime("wool_red", nil), "가위로 양털이 더 빨리 부서짐")
check(Rules.breakTime("obsidian", nil) > 30, "흑요석 맨손은 아주 오래")
check(Rules.breakTime("obsidian", "diamond_pickaxe") < 5, "다이아 곡괭이로 흑요석")
check(Rules.breakTime("bedrock", "diamond_pickaxe") == nil, "기반암은 못 부숨")
check(Rules.meleeDamage("diamond_sword", 1) == 8, "다이아 검 + 날카로움")
check(Rules.meleeDamage(nil, 1) == 1, "맨손 1")
local full = { helmet = "diamond_helmet", chestplate = "diamond_chestplate", leggings = "diamond_leggings", boots = "diamond_boots" }
check(Rules.armorPoints(full) == 20, "다이아 갑옷 20점")
check(math.abs(Rules.reduction(20, 3) - 0.8) < 1e-9, "피해 감소 최대 80%")
local health, absorption = Rules.applyDamage(10, 20, 4, 0, 0)
check(health == 14 and absorption == 0, "보호막 먼저 깎임")
local h2 = Rules.applyDamage(10, 20, 0, 20, 0)
check(math.abs(h2 - 18) < 1e-9, "다이아 갑옷이면 10 -> 2")
check(math.abs(Rules.breakTime("wool_red", nil, 1.5) - Rules.breakTime("wool_red", nil) / 1.5) < 1e-9, "광부의 손 2단계는 1.5배 빠름")
check(Rules.breakSpeed(0, false) == 1 and Rules.breakSpeed(2, false) == 1.5, "부수기 속도 배율")
check(Rules.breakSpeed(0, true) < 0.5, "피로 함정은 아주 느림")
check(Rules.knockbackMultiplier("kb_stick") > 1 and Rules.knockbackMultiplier("wood_sword") == 1, "넉백 막대기")
check(Rules.arrowDamage(1, "bow_power") > Rules.arrowDamage(1, "bow"), "힘 마법 활이 더 셈")
check(Rules.meleeDamage("diamond_sword_sharp", 0) > Rules.meleeDamage("diamond_sword", 0), "날카로움 검")
check(Rules.armorPoints({ chestplate = "iron_chestplate_prot" }) > Rules.armorPoints({ chestplate = "iron_chestplate" }), "보호 갑옷")

section("난수/투표/팀/상자")
local r1, r2 = Util.rng(5), Util.rng(5)
check(r1:next() == r2:next() and r1:int(1, 100) == r2:int(1, 100), "같은 시드는 같은 결과")
for _ = 1, 200 do
	local v = r1:int(3, 7)
	check(v >= 3 and v <= 7, "int 범위")
end
local Vote = requireModule(Server.Logic.Vote)
local function seq(values)
	local i = 0
	return function()
		i += 1
		return values[(i - 1) % #values + 1]
	end
end
check(Vote.winner({ a = "m2", b = "m2", c = "m1" }, { "m1", "m2", "m3" }, seq({ 0.5 })) == "m2", "가장 많은 표")
check(Vote.winner({}, { "m1", "m2" }, seq({ 0.99 })) == "m2", "표가 없으면 무작위")
check(Vote.winner({ a = "m1", b = "m3" }, { "m1", "m2", "m3" }, seq({ 0.1 })) == "m1", "동점 중 하나")
check(Vote.counts({ a = "zz" }, { "m1" }).m1 == 0, "없는 맵 표는 무시")
local Teams = requireModule(Server.Logic.Teams)
local teams = Teams.assign({ 1, 2, 3, 4, 5 }, 4, 2, seq({ 0.3, 0.7, 0.1 }))
local sizes = {}
for i, t in teams do
	sizes[i] = #t
end
table.sort(sizes)
check(sizes[1] == 1 and sizes[4] == 2, "팀 인원 고르게 (5명 -> 2,1,1,1)")
local two = Teams.assign({ "a", "b" }, 4, 2, seq({ 0.5 }))
check(#two[1] == 1 and #two[2] == 1 and #two[3] == 0, "2명이면 서로 다른 팀")
local Loot = requireModule(Server.Logic.Loot)
for tableName, def in Loot.Tables do
	for _, entry in def.entries do
		check(Items.get(entry.id) ~= nil, "상자 아이템 " .. entry.id)
	end
	local rolled = Loot.roll(tableName, FakeRandom.new(7).NextNumber and function()
		return math.random()
	end)
	check(#rolled >= def.rolls[1], tableName .. " 상자 개수")
end
local LuckyTable = requireModule(Server.Logic.LuckyTable)
for _, outcome in LuckyTable.Outcomes do
	check(outcome.weight > 0, "럭키 블록 확률 " .. outcome.id)
	for _, stack in outcome.items or {} do
		check(Items.get(stack.id) ~= nil, "럭키 블록 아이템 " .. stack.id)
	end
	for _, effect in outcome.effects or {} do
		check(Config.Effects[effect.name] ~= nil, "럭키 블록 효과 " .. effect.name)
	end
	local kinds = { items = true, tnt = true, mobs = true, effect = true, launch = true, lightning = true, nothing = true }
	check(kinds[outcome.kind] == true, "럭키 블록 결과 종류 " .. outcome.kind)
end
for _, r in { 0, 0.3, 0.6, 0.999 } do
	check(LuckyTable.pick(function()
		return r
	end) ~= nil, "럭키 블록 뽑기")
end
local scattered = Loot.scatter({ { id = "planks", count = 3 }, { id = "bow", count = 1 } }, 27, math.random)
local n = 0
for slot in scattered do
	n += 1
	check(slot >= 1 and slot <= 27, "상자 칸 범위")
end
check(n == 2, "흩어진 아이템 수")

section("가방")
local Inventory = requireModule(Server.Logic.Inventory)
local inv = Inventory.new()
check(Inventory.add(inv, "wool_red", 100) == 0, "양털 100개 넣기")
check(inv.slots[1].count == 64 and inv.slots[2].count == 36, "64개씩 묶음")
check(Inventory.count(inv, "wool_red") == 100, "개수 세기")
check(Inventory.remove(inv, "wool_red", 40), "40개 빼기")
check(Inventory.count(inv, "wool_red") == 60, "남은 60개")
check(not Inventory.remove(inv, "wool_red", 61), "모자라면 못 뺌")
Inventory.click(inv, inv.slots, 1, "left")
check(inv.cursor ~= nil and inv.slots[1] == nil, "왼쪽 클릭으로 집기")
Inventory.click(inv, inv.slots, 5, "left")
check(inv.cursor == nil and inv.slots[5] ~= nil, "빈 칸에 놓기")
Inventory.click(inv, inv.slots, 5, "right")
check(inv.cursor ~= nil and inv.cursor.count == math.ceil(60 / 2), "오른쪽 클릭으로 반 집기")
Inventory.click(inv, inv.slots, 7, "right")
check(inv.slots[7] and inv.slots[7].count == 1, "오른쪽 클릭으로 하나 놓기")
Inventory.returnCursor(inv)
check(inv.cursor == nil and Inventory.count(inv, "wool_red") == 60, "커서 돌려놓기")
local chest = {}
check(Inventory.quickMove(inv.slots, 5, chest, 27), "쉬프트 클릭으로 상자에 넣기")
check(chest[1] ~= nil, "상자 첫 칸")
Inventory.clear(inv)
inv.slots[3] = { id = "iron_chestplate", count = 1 }
check(Inventory.equipFromSlot(inv, 3) and inv.armor.chestplate == "iron_chestplate" and inv.slots[3] == nil, "갑옷 착용")
inv.cursor = { id = "diamond_helmet", count = 1 }
check(not Inventory.clickArmor(inv, "boots"), "부위가 다르면 못 낌")
check(Inventory.clickArmor(inv, "helmet") and inv.armor.helmet == "diamond_helmet", "투구 칸에 끼기")
inv.armorLocked = true
check(not Inventory.clickArmor(inv, "helmet"), "잠긴 갑옷은 못 뺌")
Inventory.clear(inv)
inv.slots[2] = { id = "wood_sword", count = 1 }
Inventory.replaceKind(inv, "sword", "iron_sword")
check(inv.slots[2].id == "iron_sword", "검 교체")
check(Inventory.canFit(inv, "wool_red", 64 * 35), "빈 칸 계산")
check(not Inventory.canFit(inv, "wool_red", 64 * 35 + 1), "꽉 참 계산")
inv.slots[12] = { id = "arrow", count = 5 }
check(Inventory.quickMoveInside(inv, 12) and inv.slots[1] and inv.slots[1].id == "arrow", "가방 -> 핫바")
local data = Inventory.serialize(inv)
check(data.slots["1"][1] == "arrow" and data.slots["2"][1] == "iron_sword", "보내기용 변환")

-- 맵 검사용 가짜 그리기 도구
local function fakeApi()
	local blocks = {}
	local markers = {}
	local api = {}
	local function key(x, y, z)
		return (x + 0) .. "," .. (y + 0) .. "," .. (z + 0)
	end
	function api.set(x, y, z, id)
		assert(Blocks.get(id), "없는 블록 " .. tostring(id))
		assert(math.floor(x) == x and math.floor(y) == y and math.floor(z) == z, "정수 좌표가 아님")
		blocks[key(x, y, z)] = id
	end
	function api.get(x, y, z)
		return blocks[key(x, y, z)]
	end
	function api.clear(x, y, z)
		blocks[key(x, y, z)] = nil
	end
	function api.fill(x1, y1, z1, x2, y2, z2, id)
		for x = math.min(x1, x2), math.max(x1, x2) do
			for y = math.min(y1, y2), math.max(y1, y2) do
				for z = math.min(z1, z2), math.max(z1, z2) do
					api.set(x, y, z, id)
				end
			end
		end
	end
	function api.marker(kind, value)
		markers[kind] = markers[kind] or {}
		table.insert(markers[kind], value)
	end
	function api.big(_x1, _y1, _z1, _x2, _y2, _z2, id)
		assert(Blocks.get(id), "없는 블록 " .. tostring(id))
	end
	function api.part(_x, _y, _z, sx, sy, sz, id, _opts)
		assert(Blocks.get(id), "없는 장식 블록 " .. tostring(id))
		assert(sx > 0 and sy > 0 and sz > 0, "장식 크기가 0 이하")
	end
	local count = function()
		local c = 0
		for _ in blocks do
			c += 1
		end
		return c
	end
	return api, blocks, markers, count
end

local function solid(api, x, y, z)
	local id = api.get(x, y, z)
	local def = id and Blocks.get(id)
	return def ~= nil and def.canCollide ~= false
end
local function free(api, x, y, z)
	return api.get(x, y, z) == nil
end
local function xyz(t)
	if t.x then
		return t.x, t.y, t.z
	end
	return t[1], t[2], t[3]
end

local summary = {}

section("베드워즈 맵")
for _, map in requireModule(Server.Maps.BedWarsMaps) do
	local api, _, markers, count = fakeApi()
	map.build(api, Util.rng(map.seed))
	table.insert(summary, string.format("베드워즈 %s: 블록 %d개", map.name, count()))
	check(#(markers.team or {}) == 4, map.id .. " 팀 4개")
	check(#(markers.diamond or {}) == 4, map.id .. " 다이아 생성기 4개")
	check(#(markers.emerald or {}) == 2, map.id .. " 에메랄드 생성기 2개")
	for _, t in markers.team or {} do
		local sx, sy, sz = xyz(t.spawn)
		check(solid(api, sx, sy - 1, sz) and free(api, sx, sy, sz) and free(api, sx, sy + 1, sz), map.id .. " " .. t.team .. " 스폰 자리")
		for _, cell in { t.bedFoot, t.bedHead, t.gen } do
			local x, y, z = xyz(cell)
			check(solid(api, x, y - 1, z) and free(api, x, y, z), map.id .. " " .. t.team .. " 침대/생성기 자리")
		end
		-- 팀 상자 / 개인 상자
		local chestX, chestY, chestZ = xyz(t.chest)
		check(api.get(chestX, chestY, chestZ) == "chest" and solid(api, chestX, chestY - 1, chestZ), map.id .. " " .. t.team .. " 팀 상자")
		local enderX, enderY, enderZ = xyz(t.ender)
		check(api.get(enderX, enderY, enderZ) == "ender_chest" and solid(api, enderX, enderY - 1, enderZ), map.id .. " " .. t.team .. " 개인 상자")
		check(free(api, chestX, chestY + 1, chestZ) and free(api, enderX, enderY + 1, enderZ), map.id .. " 상자 위가 비어 있음")
		for _, cell in { t.shop, t.upgrades } do
			local x, y, z = xyz(cell)
			check(solid(api, x, y - 1, z) and free(api, x, y, z) and free(api, x, y + 1, z) and free(api, x, y + 2, z), map.id .. " " .. t.team .. " NPC 자리")
		end
		-- 스폰에서 침대까지 막힌 블록이 없어야 함 (같은 줄)
		local fx, _, fz = xyz(t.bedFoot)
		local sx2, _, sz2 = xyz(t.spawn)
		local stepX, stepZ = math.sign(fx - sx2), math.sign(fz - sz2)
		local cx, cz = sx2, sz2
		local clear = true
		while cx ~= fx or cz ~= fz do
			if not free(api, cx, 1, cz) or not free(api, cx, 2, cz) then
				clear = false
			end
			cx += stepX
			cz += stepZ
		end
		check(clear, map.id .. " " .. t.team .. " 스폰에서 침대까지 길이 열려 있음")
	end
	for _, list in { markers.diamond or {}, markers.emerald or {} } do
		for _, cell in list do
			local x, y, z = xyz(cell)
			check(solid(api, x, y - 1, z) and free(api, x, y, z), map.id .. " 생성기 자리")
		end
	end
end

section("스카이워즈 맵")
for _, map in requireModule(Server.Maps.SkyWarsMaps) do
	local api, _, markers, count = fakeApi()
	map.build(api, Util.rng(map.seed))
	table.insert(summary, string.format("스카이워즈 %s: 블록 %d개", map.name, count()))
	check(#(markers.spawn or {}) == 8, map.id .. " 시작 섬 8개")
	check(#(markers.chest or {}) >= 16 + 4 + 4, map.id .. " 상자 수 " .. #(markers.chest or {}))
	for _, s in markers.spawn or {} do
		check(solid(api, s.x, s.y - 1, s.z), map.id .. " 스폰 바닥")
		-- 유리관 안쪽(사람이 서는 두 칸)은 비어 있어야 함. 벽 자리 장식은 유리관이 열리면 되돌아옴
		for dy = 0, 2 do
			local id = api.get(s.x, s.y + dy, s.z)
			check(id == nil, string.format("%s 유리관 안쪽이 비어 있음 (%d,%d,%d)=%s", map.id, s.x, s.y + dy, s.z, tostring(id)))
		end
		for dx = -1, 1 do
			for dz = -1, 1 do
				for dy = 0, 2 do
					check(api.get(s.x + dx, s.y + dy, s.z + dz) ~= "chest", map.id .. " 유리관 자리에 상자 없음")
				end
			end
		end
	end
	for _, c in markers.chest or {} do
		check(api.get(c.x, c.y, c.z) == "chest", map.id .. string.format(" 상자 블록이 남아 있음 (%d,%d,%d)", c.x, c.y, c.z))
		check(solid(api, c.x, c.y - 1, c.z), map.id .. " 상자 바닥")
	end
	-- 럭키 블록 자리: 대부분 바닥이 있고 비어 있어야 함 (아닌 자리는 경기 때 건너뜀)
	local luckyOk = 0
	for _, l in markers.lucky or {} do
		local id = api.get(l.x, l.y, l.z)
		local def = if id then Blocks.get(id) else nil
		if solid(api, l.x, l.y - 1, l.z) and (id == nil or (def and def.canCollide == false)) then
			luckyOk += 1
		end
		for _, s in markers.spawn or {} do
			check(math.abs(l.x - s.x) > 1 or math.abs(l.z - s.z) > 1, map.id .. " 럭키 블록이 유리관 자리에 없음")
		end
	end
	check(#(markers.lucky or {}) >= 20, map.id .. " 럭키 블록 자리 수")
	check(luckyOk >= #(markers.lucky or {}) * 0.6, string.format("%s 럭키 블록 자리 %d/%d 가 쓸 수 있음", map.id, luckyOk, #(markers.lucky or {})))
end

section("TNT 런 맵")
for _, map in requireModule(Server.Maps.TntRunMaps) do
	local api, _, markers, count = fakeApi()
	map.build(api, Util.rng(map.seed))
	table.insert(summary, string.format("TNT 런 %s: 블록 %d개", map.name, count()))
	check(#(markers.floor or {}) == 4, map.id .. " 4층")
	check(#(markers.spawn or {}) == 12, map.id .. " 스폰 12개")
	for _, s in markers.spawn or {} do
		check(solid(api, s.x, s.y - 1, s.z) and free(api, s.x, s.y, s.z), map.id .. " 스폰 자리")
	end
end

section("로비/대기실")
do
	local Lobby = requireModule(Server.Maps.Lobby)
	do
		local W = requireModule(Server.World)
		local lobbyWorld = W.new(roblox.Vector3.new(0, 60, 0), "LobbyTest")
		Lobby.build(lobbyWorld:builder({}), Util.rng(42))
		lobbyWorld:show(workspace)
		table.insert(summary, string.format("로비: 기록 %d개 중 실제 파트 %d개", lobbyWorld.count, lobbyWorld.partCount))
		check(lobbyWorld.partCount < 16000, "로비 파트 수 " .. lobbyWorld.partCount)
		lobbyWorld:destroy()
	end
	local api, _, markers, count = fakeApi()
	Lobby.build(api, Util.rng(42))
	table.insert(summary, string.format("로비: 블록 %d개", count()))
	local s = markers.spawn[1]
	check(solid(api, s.x, s.y - 1, s.z) and free(api, s.x, s.y, s.z) and free(api, s.x, s.y + 1, s.z), "로비 스폰")
	check(#markers.npc == 3, "NPC 3명")
	for _, npc in markers.npc do
		check(solid(api, npc.x, npc.y - 1, npc.z) and free(api, npc.x, npc.y, npc.z) and free(api, npc.x, npc.y + 1, npc.z) and free(api, npc.x, npc.y + 2, npc.z), "NPC 자리 " .. npc.mode)
	end
	check(#(markers.portal or {}) == 3, "포털 3개")
	for _, p in markers.portal or {} do
		local open = true
		for dx = -1, 1 do
			for dy = 0, p.h - 1 do
				if not free(api, p.x + dx, p.y + dy, p.z) then
					open = false
				end
			end
		end
		check(open and solid(api, p.x, p.y - 1, p.z), "포털 안쪽이 비어 있음 " .. p.mode)
		-- 포털 앞에서 걸어 들어갈 수 있어야 함
		check(free(api, p.x, p.y, p.z - 1) and free(api, p.x, p.y + 1, p.z - 1), "포털 앞 " .. p.mode)
	end
	-- 파쿠르: 앞 발판에서 다음 발판까지 많아야 한 칸 띄우고, 한 칸 위까지만
	local jumps = markers.parkour[1]
	check(#jumps >= 20, "파쿠르 발판 수")
	for i = 2, #jumps do
		local a, b = jumps[i - 1], jumps[i]
		local gap = math.max(math.abs(a[1] - b[1]), math.abs(a[3] - b[3]))
		check(gap <= 2 and gap >= 1, string.format("파쿠르 %d번 거리 %d", i, gap))
		check(b[2] - a[2] <= 1, string.format("파쿠르 %d번 높이", i))
		check(free(api, b[1], b[2] + 1, b[3]) and free(api, b[1], b[2] + 2, b[3]), string.format("파쿠르 %d번 머리 위", i))
	end
	local last = jumps[#jumps]
	local finish = markers.parkourEnd[1]
	check(math.max(math.abs(last[1] - finish.x), math.abs(last[3] - finish.z)) <= 7 and finish.y - last[2] <= 2, "파쿠르 끝이 마지막 발판 근처")
	check(free(api, finish.x, finish.y, finish.z) and solid(api, finish.x, finish.y - 1, finish.z), "파쿠르 도착 지점")
	local Rooms = requireModule(Server.Maps.Rooms)
	for _, name in { "waiting", "spectator" } do
		local rapi, _, rm = fakeApi()
		Rooms[name](rapi, Util.rng(1))
		local r = rm.spawn[1]
		check(solid(rapi, r.x, r.y - 1, r.z) and free(rapi, r.x, r.y, r.z), name .. " 스폰")
	end
end

section("스킨/캐릭터/설정")
do
	local Skins = requireModule(Shared.Skins)
	local CharacterRig = requireModule(Shared.CharacterRig)
	local Preferences = requireModule(Shared.Preferences)
	check(Skins.decode(Skins.DefaultCode).shirt == Skins.Default.shirt, "기본 스킨 되돌리기")
	check(Skins.isDefault(Skins.DefaultCode), "기본 스킨 판별")
	check(Skins.encode(Skins.decode("이상한 값")) == Skins.DefaultCode, "잘못된 스킨은 기본으로")
	check(Skins.decode("v1|ZZZZZZ|2B1E16").skin == Skins.Default.skin, "팔레트 밖 색은 기본으로")
	for _, preset in Skins.Presets do
		local clean = Skins.sanitize(preset.skin)
		for key, value in preset.skin do
			check(clean[key] == value, "프리셋 " .. preset.name .. " " .. key .. " 이 팔레트 안에 있음")
		end
		local code = Skins.encode(preset.skin)
		check(Skins.encode(Skins.decode(code)) == code, "프리셋 왕복 " .. preset.name)
		check(#code < 120, "스킨 코드 길이")
	end
	for _, hairStyle in Skins.HairStyles do
		for _, shirtStyle in Skins.ShirtStyles do
			local skin = Skins.sanitize({ hairStyle = hairStyle, shirtStyle = shirtStyle })
			for partName in Skins.Parts do
				for _, face in { "Front", "Back", "Left", "Right", "Top", "Bottom" } do
					local px, w, h = Skins.face(skin, partName, face)
					local okSize = #px == w * h * 4
					local okValues = true
					for i = 1, #px do
						local v = px[i]
						if type(v) ~= "number" or v < 0 or v > 255 or v ~= math.floor(v) then
							okValues = false
						end
					end
					check(okSize and okValues, string.format("스킨 픽셀 %s/%s %s %s", hairStyle, shirtStyle, partName, face))
				end
			end
		end
	end
	local rig = CharacterRig.build()
	for _, name in CharacterRig.BodyParts do
		check(rig:FindFirstChild(name) ~= nil, "캐릭터 부위 " .. name)
	end
	local root = rig:FindFirstChild("HumanoidRootPart")
	check(root ~= nil and rig.PrimaryPart == root, "루트 파트")
	check(math.abs(root.CFrame.Position.Y - 3) < 1e-6, "루트 높이 3 (일반 R6 와 같음)")
	local leg = rig:FindFirstChild("Left Leg")
	check(math.abs(leg.CFrame.Position.Y - leg.Size.Y / 2) < 1e-6, "다리 바닥이 땅(0)에 닿음")
	check(math.abs(leg.Size.Y - 2) < 1e-6, "다리 길이 2 (R6 서 있는 높이 계산과 맞음)")
	local torso = rig:FindFirstChild("Torso")
	for _, name in { "Neck", "Right Shoulder", "Left Shoulder", "Right Hip", "Left Hip" } do
		local m = torso:FindFirstChild(name)
		check(m ~= nil and m.ClassName == "Motor6D" and m.Part0 == torso and m.Part1 ~= nil, "관절 " .. name)
		if m then
			-- 관절을 따라 계산한 위치가 실제 부위 위치와 같아야 함
			local expected = m.Part0.CFrame * m.C0 * m.C1:Inverse()
			check((expected.Position - m.Part1.CFrame.Position).Magnitude < 1e-5, "관절 위치 " .. name)
		end
	end
	local rootJoint = root:FindFirstChild("RootJoint")
	check(rootJoint ~= nil and rootJoint.Part1 == torso, "RootJoint")
	local humanoid = rig:FindFirstChildOfClass("Humanoid")
	check(humanoid ~= nil and humanoid.RigType == roblox.Enum.HumanoidRigType.R6, "R6 휴머노이드")
	check(rig:GetAttribute("BlockRig") == true, "블록 캐릭터 표시")
	local defaults = Preferences.defaults()
	check(defaults.camera == "first" and defaults.textures == true, "설정 기본값")
	local clean = Preferences.sanitize({ camera = "third", sounds = false, textureDistance = 9, hack = true })
	check(clean.camera == "third" and clean.sounds == false and clean.textureDistance == 2 and clean.hack == nil, "설정 검사")
end

section("텍스처")
local Textures = requireModule(Shared.Textures)
local names = Textures.names()
check(#names >= 80, "텍스처 수 " .. #names)
for _, name in names do
	local px = Textures.get(name)
	check(px ~= nil and #px == 1024, "텍스처 " .. name)
	if px then
		local okValues = true
		for i = 1, 1024 do
			local v = px[i]
			if type(v) ~= "number" or v < 0 or v > 255 or v ~= math.floor(v) then
				okValues = false
			end
		end
		check(okValues, "텍스처 값 범위 " .. name)
	end
end

section("숨은 블록 생략")
do
	local World = requireModule(Server.World)
	local world = World.new(roblox.Vector3.new(0, 0, 0), "CullTest")
	local api = world:builder({})
	api.fill(0, 0, 0, 4, 4, 4, "stone")
	check(world.partCount == 0, "그리는 동안은 파트 없음")
	world:show(workspace)
	check(world.count == 125, "기록 125개")
	check(world.partCount == 98, "보이는 블록만 파트 (98개) -> " .. world.partCount)
	check(world:get(2, 2, 2).part == nil, "가운데 블록은 파트 없음")
	local top = world:get(2, 4, 2)
	check(top.part ~= nil and top.part:GetAttribute("Occ") == 63 - 4, "윗면만 보이는 블록의 가려진 면 값")
	world:remove(2, 4, 2)
	check(world:get(2, 3, 2).part ~= nil, "부서지면 밑의 블록이 드러남")
	check(world:get(2, 3, 2).part:GetAttribute("Occ") == 63 - 4, "드러난 블록의 가려진 면 값")
	check(world:get(1, 4, 2).part:GetAttribute("Occ") == 63 - 4 - 1, "옆 블록 면 값 갱신")
	world:set(2, 4, 2, "glass", true)
	check(world:get(2, 3, 2).part:GetAttribute("Occ") == 63 - 4, "유리는 면을 가리지 않음")
	world:set(2, 5, 2, "stone", true)
	check(world:get(2, 5, 2).part:GetAttribute("Occ") == 8 - 8, "유리 위 돌")
	-- 사다리는 붙은 벽 쪽으로 붙어 있어야 함
	for _, face in { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } } do
		world:set(10, 0, 10, "ladder", true, { face = face })
		local ladder = world:get(10, 0, 10).part
		local offset = ladder.CFrame.Position - world:cellCenter(10, 0, 10)
		check(math.abs(offset.X - face[1] * 0.44 * 3) < 0.01 and math.abs(offset.Z - face[2] * 0.44 * 3) < 0.01, string.format("사다리 방향 (%d,%d)", face[1], face[2]))
		world:remove(10, 0, 10)
	end
	world:destroy()
end

section("몹 움직임")
do
	local World = requireModule(Server.World)
	local MobMotion = requireModule(Server.Logic.MobMotion)
	local V = roblox.Vector3
	local world = World.new(V.new(0, 0, 0), "MobTest")
	local api = world:builder({})
	api.fill(0, 0, -2, 20, 0, 2, "stone")
	api.set(8, 1, 0, "stone") -- 한 칸 턱: 올라감
	api.fill(14, 1, -2, 14, 3, 2, "stone") -- 세 칸 벽: 막힘
	local B = 3
	local feet = world:cellCenter(2, 0, 0) + V.new(0, B / 2, 0)
	local velocity = V.new(0, 0, 0)
	local wish = V.new(9, 0, 0)
	local climbed = false
	for _ = 1, 600 do
		feet, velocity = MobMotion.ground(world, feet, velocity, wish, 1 / 30, B)
		if math.abs(feet.X - 8 * B) < 1 and feet.Y > world:cellCenter(0, 1, 0).Y + 1 then
			climbed = true
		end
	end
	check(climbed, "한 칸 턱을 올라감")
	check(feet.X < 14 * B - B / 2 + 0.01 and feet.X > 12 * B, string.format("세 칸 벽 앞에서 멈춤 (x=%.2f)", feet.X))
	check(math.abs(feet.Y - (world:cellCenter(0, 0, 0).Y + B / 2)) < 0.01, string.format("벽 앞 바닥에 서 있음 (y=%.2f)", feet.Y))
	-- 높은 곳에서 떨어지면 바닥에 착지
	local fall = world:cellCenter(4, 12, 0)
	local fv = V.new(0, 0, 0)
	for _ = 1, 120 do
		fall, fv = MobMotion.ground(world, fall, fv, V.new(0, 0, 0), 1 / 30, B)
	end
	check(math.abs(fall.Y - (world:cellCenter(4, 0, 0).Y + B / 2)) < 0.01 and fv.Y == 0, string.format("떨어져서 착지 (y=%.2f)", fall.Y))
	-- 넉백은 마찰로 멈춤
	local kb = world:cellCenter(4, 0, 0) + V.new(0, B / 2, 0)
	local kv = V.new(20, 0, 0)
	for _ = 1, 60 do
		kb, kv = MobMotion.ground(world, kb, kv, V.new(0, 0, 0), 1 / 30, B)
	end
	check(V.new(kv.X, 0, kv.Z).Magnitude < 0.5, "넉백 속도가 줄어듦")
	world:destroy()
end

section("실제 인스턴스로 맵 만들기")
local World = requireModule(Server.World)
local Npc = requireModule(Server.Npc)
local maxParts = 0
for _, group in { "BedWarsMaps", "SkyWarsMaps", "TntRunMaps" } do
	for _, map in requireModule(Server.Maps[group]) do
		local world = World.new(roblox.Vector3.new(2400, 160, 0), "Test_" .. map.id)
		local markers = {}
		local ok, err = pcall(function()
			map.build(world:builder(markers), Util.rng(map.seed))
		end)
		check(ok, map.id .. " 실제 생성: " .. tostring(err))
		world:show(workspace)
		maxParts = math.max(maxParts, world.partCount)
		table.insert(summary, string.format("  -> %s: 기록 %d개 중 실제 파트 %d개", map.id, world.count, world.partCount))
		local x, y, z = 0, 40, 0
		world:set(x, y, z, "wool_red", true)
		local record = world:get(x, y, z)
		check(record ~= nil and record.placed and record.part.Parent == world.blocksFolder, map.id .. " 설치 기록")
		local cx, cy, cz = world:toCell(record.part.CFrame.Position)
		check(cx == x and cy == y and cz == z, map.id .. " 좌표 되돌리기")
		world:remove(x, y, z)
		check(world:get(x, y, z) == nil, map.id .. " 블록 제거")
		world:show(workspace)
		world:destroy()
	end
end
table.insert(summary, "가장 큰 맵 파트 수: " .. maxParts)
check(maxParts < 15000, "파트 수가 적당함 (" .. maxParts .. ")")
do
	local world = World.new(roblox.Vector3.new(0, 60, 0), "NpcTest")
	local ok, err = pcall(function()
		Npc.create(world.extras, roblox.Vector3.new(0, 60, 0), roblox.Vector3.new(0, 60, 10), {
			name = "상점",
			subtitle = "F",
			shirt = roblox.Color3.new(1, 0, 0),
			actionText = "열기",
		})
	end)
	check(ok, "NPC 만들기: " .. tostring(err))
	-- 침대, 상자처럼 크기가 다른 블록도 좌표가 맞는지
	for _, id in { "bed_red", "chest" } do
		local record = world:set(3, 2, -4, id)
		local cx, cy, cz = world:toCell(record.part.CFrame.Position)
		check(cx == 3 and cy == 2 and cz == -4, id .. " 좌표")
	end
end

print("")
for _, line in summary do
	print("  " .. line)
end
print(string.format("\n통과 %d / 실패 %d", passed, failed))
if failed > 0 then
	process.exit(1)
end
