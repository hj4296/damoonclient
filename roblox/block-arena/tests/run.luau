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
end

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
		for _, cell in { t.shop, t.upgrades } do
			local x, y, z = xyz(cell)
			check(solid(api, x, y - 1, z) and free(api, x, y, z) and free(api, x, y + 1, z), map.id .. " " .. t.team .. " NPC 자리")
		end
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
	local api, _, markers, count = fakeApi()
	Lobby.build(api, Util.rng(42))
	table.insert(summary, string.format("로비: 블록 %d개", count()))
	local s = markers.spawn[1]
	check(solid(api, s.x, s.y - 1, s.z) and free(api, s.x, s.y, s.z) and free(api, s.x, s.y + 1, s.z), "로비 스폰")
	check(#markers.npc == 3, "NPC 3명")
	for _, npc in markers.npc do
		check(solid(api, npc.x, npc.y - 1, npc.z) and free(api, npc.x, npc.y, npc.z) and free(api, npc.x, npc.y + 1, npc.z), "NPC 자리 " .. npc.mode)
	end
	local Rooms = requireModule(Server.Maps.Rooms)
	for _, name in { "waiting", "spectator" } do
		local rapi, _, rm = fakeApi()
		Rooms[name](rapi, Util.rng(1))
		local r = rm.spawn[1]
		check(solid(rapi, r.x, r.y - 1, r.z) and free(rapi, r.x, r.y, r.z), name .. " 스폰")
	end
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
		maxParts = math.max(maxParts, world.count)
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
check(maxParts < 12000, "파트 수가 적당함")
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
