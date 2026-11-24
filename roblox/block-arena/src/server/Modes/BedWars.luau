-- 베드워즈: 4팀, 침대가 있으면 부활, 자원 생성기와 상점, 팀 강화, 시간 이벤트
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Items = require(Shared.Items)
local Blocks = require(Shared.Blocks)
local Shop = require(Shared.Shop)
local Util = require(Shared.Util)
local Server = script.Parent.Parent
local Session = require(Server.Session)
local Net = require(Server.Net)
local Inventory = require(Server.Logic.Inventory)
local Teams = require(Server.Logic.Teams)
local Npc = require(Server.Npc)

local B = Config.BlockSize
local BW = Config.BedWars

local BedWars = {}
local Matches: any = nil

local ARMOR_ORDER = { leather = 1, chain = 2, iron = 3, diamond = 4 }
local CURRENCY_NAMES = { iron = "철", gold = "금", diamond = "다이아몬드", emerald = "에메랄드" }
local CURRENCY_HEX = { iron = "#FFFFFF", gold = "#FFAA00", diamond = "#55FFFF", emerald = "#55FF55" }

function BedWars.init(matches)
	Matches = matches
end

local random = Random.new()
local function rand(): number
	return random:NextNumber()
end

local function cell(t: { number }): (number, number, number)
	return t[1], t[2], t[3]
end

local function spawnCFrame(match, teamId: string): CFrame
	local marker = match.data.teams[teamId].marker
	local x, y, z = cell(marker.spawn)
	return match.world:standCFrame(x, y, z, match.world:cellCenter(0, y, 0))
end

local function giveLoadout(match, player: Player)
	local state = Session.get(player)
	local pd = match.data.players[player]
	if not state or not pd then
		return
	end
	local inv = state.inv
	Inventory.clear(inv)
	inv.slots[1] = { id = "wood_sword", count = 1 }
	local pickaxe = Shop.getItem("pickaxe")
	local axe = Shop.getItem("axe")
	if pd.pickaxe > 0 and pickaxe and pickaxe.tiers then
		Inventory.add(inv, pickaxe.tiers[pd.pickaxe].give, 1)
	end
	if pd.axe > 0 and axe and axe.tiers then
		Inventory.add(inv, axe.tiers[pd.axe].give, 1)
	end
	if pd.shears then
		Inventory.add(inv, "shears", 1)
	end
	inv.armor = {
		helmet = "leather_helmet",
		chestplate = "leather_chestplate",
		leggings = pd.armor .. "_leggings",
		boots = pd.armor .. "_boots",
	}
	inv.armorLocked = true
	inv.dirty = true
end

local function contextFor(match, player: Player)
	local pd = match.data.players[player]
	return { location = "match", mode = "bedwars", build = true, pvp = true, team = if pd then pd.team else nil }
end

local function spawnPlayer(match, player: Player)
	local pd = match.data.players[player]
	if not pd then
		return
	end
	Session.setContext(player, contextFor(match, player))
	Session.spawn(player, spawnCFrame(match, pd.team), function()
		giveLoadout(match, player)
	end)
end

local function makeDisplay(match, position: Vector3, color: Color3, size: number, title: string)
	local part = Instance.new("Part")
	part.Name = "GeneratorDisplay"
	part.Anchored = true
	part.CanCollide = false
	part.CanQuery = false
	part.CanTouch = false
	part.Size = Vector3.new(size, size, size)
	part.Color = color
	part.Material = Enum.Material.SmoothPlastic
	part.CFrame = CFrame.new(position) * CFrame.Angles(math.rad(35), math.rad(45), 0)
	part:AddTag("Spin")
	part.Parent = match.world.extras

	local gui = Instance.new("BillboardGui")
	gui.Size = UDim2.new(7, 0, 2.6, 0)
	gui.StudsOffset = Vector3.new(0, size + 1.2, 0)
	gui.MaxDistance = 90
	gui.LightInfluence = 0
	gui.Parent = part
	local head = Instance.new("TextLabel")
	head.BackgroundTransparency = 1
	head.Size = UDim2.new(1, 0, 0.5, 0)
	head.Font = Enum.Font.Arcade
	head.TextScaled = true
	head.RichText = true
	head.TextStrokeTransparency = 0.3
	head.TextColor3 = Color3.new(1, 1, 1)
	head.Text = title
	head.Parent = gui
	local info = head:Clone()
	info.Position = UDim2.new(0, 0, 0.5, 0)
	info.Size = UDim2.new(1, 0, 0.5, 0)
	info.Text = ""
	info.Parent = gui
	return { part = part, title = head, info = info }
end

local function teamOf(match, player: Player)
	local pd = match.data.players[player]
	return if pd then match.data.teams[pd.team] else nil
end

local function aliveTeams(match)
	local out = {}
	for _, team in match.data.teamOrder do
		local alive = 0
		for player in team.members do
			local pd = match.data.players[player]
			local state = Session.get(player)
			if pd and not pd.eliminated and player.Parent and state and state.match == match then
				alive += 1
			end
		end
		team.alive = alive
		if alive > 0 then
			table.insert(out, team)
		end
	end
	return out
end

local function checkWin(match)
	if match.state ~= "playing" then
		return
	end
	local alive = aliveTeams(match)
	for _, team in match.data.teamOrder do
		if team.started and team.alive == 0 and not team.announcedOut then
			team.announcedOut = true
			Session.chat(
				match.players,
				string.format('<b>팀 탈락</b> > <font color="%s">%s 팀</font>이 탈락했습니다!', team.def.hex, team.def.name)
			)
		end
	end
	if match.practice then
		if #alive == 0 then
			Matches.finish(match, {}, "연습이 끝났습니다.")
		end
		return
	end
	if #alive <= 1 then
		local winners = {}
		local headline = "무승부!"
		local team = alive[1]
		if team then
			for player in team.members do
				if player.Parent then
					table.insert(winners, player)
				end
			end
			headline = string.format('<font color="%s">%s 팀</font>이 승리했습니다!', team.def.hex, team.def.name)
		end
		Matches.finish(match, winners, headline)
	end
end

local function destroyBed(match, teamId: string, breaker: Player?)
	local team = match.data.teams[teamId]
	if not team or not team.bed then
		return
	end
	team.bed = false
	local marker = team.marker
	match.world:remove(cell(marker.bedFoot))
	match.world:remove(cell(marker.bedHead))
	local members = {}
	for player in team.members do
		table.insert(members, player)
	end
	local name = string.format('<font color="%s">%s 팀</font>', team.def.hex, team.def.name)
	if breaker then
		Session.chat(match.players, string.format("<b>침대 파괴</b> > %s의 침대가 %s님에 의해 파괴되었습니다!", name, Matches.coloredName(breaker)))
		local pd = match.data.players[breaker]
		if pd then
			pd.beds += 1
		end
	else
		Session.chat(match.players, string.format("<b>침대 파괴</b> > %s의 침대가 사라졌습니다!", name))
	end
	Session.title(members, '<font color="#FF5555">침대가 파괴되었습니다!</font>', "이제 죽으면 부활할 수 없습니다", 3)
	Net.sendMany(match.players, "sound", "bed")
	checkWin(match)
end

function BedWars.start(match, markers)
	local world = match.world
	local data = {
		teams = {},
		teamOrder = {},
		players = {},
		gens = {},
		nextEvent = 1,
		elapsed = 0,
		diamondTier = 1,
		emeraldTier = 1,
		protected = {},
		displayTimer = 0,
	}
	match.data = data
	match.voidY = match.origin.Y - 30 * B

	local teamMarkers = {}
	for _, marker in markers.team or {} do
		teamMarkers[marker.team] = marker
	end
	local groups = Teams.assign(match.players, #Config.Teams, match.def.teamSize, rand)

	for i, def in Config.Teams do
		local marker = teamMarkers[def.id]
		local members = groups[i] or {}
		local team = {
			id = def.id,
			def = def,
			marker = marker,
			members = {},
			bed = false,
			alive = 0,
			started = #members > 0,
			announcedOut = false,
			upgrades = { sharpness = 0, protection = 0, forge = 0, healpool = 0 },
		}
		data.teams[def.id] = team
		table.insert(data.teamOrder, team)
		if marker then
			if #members > 0 then
				team.bed = true
				local fx, fy, fz = cell(marker.bedFoot)
				local hx, hy, hz = cell(marker.bedHead)
				world:set(fx, fy, fz, "bed_" .. def.id, false, { team = def.id })
				local head = world:set(hx, hy, hz, "bed_" .. def.id, false, { team = def.id })
				if head and head.part then
					local pillow = Instance.new("Part")
					pillow.Name = "Pillow"
					pillow.Anchored = true
					pillow.CanCollide = false
					pillow.CanQuery = false
					pillow.Size = Vector3.new(B * 0.8, 0.35, B * 0.5)
					pillow.Color = Color3.fromRGB(240, 240, 240)
					pillow.CFrame = head.part.CFrame * CFrame.new(0, head.part.Size.Y / 2 + 0.15, 0)
					pillow.Parent = head.part
				end
			end
			-- 섬 자원 생성기
			local gx, gy, gz = cell(marker.gen)
			local genPos = world:cellCenter(gx, gy, gz)
			table.insert(data.gens, {
				kind = "island",
				team = def.id,
				pos = genPos,
				iron = 0,
				gold = 0,
				ironTimer = 0,
				goldTimer = 0,
				display = makeDisplay(match, genPos + Vector3.new(0, 1, 0), Items.TierColors.iron, 0.9, "자원 생성기"),
			})
			table.insert(data.protected, { gx, gy, gz, 1 })
			local sx, sy, sz = cell(marker.spawn)
			table.insert(data.protected, { sx, sy, sz, 1 })

			-- 상점 NPC
			local lx, ly, lz = cell(marker.npcLook)
			local look = world:cellCenter(lx, ly, lz)
			local shopX, shopY, shopZ = cell(marker.shop)
			local _, shopPrompt = Npc.create(world.extras, world:cellCenter(shopX, shopY, shopZ) - Vector3.new(0, B / 2, 0), look, {
				name = "아이템 상점",
				subtitle = "F 키",
				shirt = Color3.fromRGB(70, 120, 60),
				actionText = "상점 열기",
			})
			shopPrompt.Triggered:Connect(function(player)
				BedWars.openShop(match, player, "items")
			end)
			table.insert(data.protected, { shopX, shopY, shopZ, 0 })
			local upX, upY, upZ = cell(marker.upgrades)
			local _, upPrompt = Npc.create(world.extras, world:cellCenter(upX, upY, upZ) - Vector3.new(0, B / 2, 0), look, {
				name = "팀 강화",
				subtitle = "F 키",
				shirt = Color3.fromRGB(70, 80, 160),
				actionText = "강화 열기",
			})
			upPrompt.Triggered:Connect(function(player)
				BedWars.openShop(match, player, "upgrades")
			end)
			table.insert(data.protected, { upX, upY, upZ, 0 })
		end
		for _, player in members do
			team.members[player] = true
			local state = Session.get(player)
			if state then
				state.team = def.id
			end
			data.players[player] = {
				team = def.id,
				armor = "leather",
				pickaxe = 0,
				axe = 0,
				shears = false,
				kills = 0,
				finals = 0,
				beds = 0,
				eliminated = false,
			}
		end
	end

	for _, marker in markers.diamond or {} do
		local x, y, z = cell(marker)
		local pos = world:cellCenter(x, y, z)
		table.insert(data.gens, {
			kind = "diamond",
			pos = pos,
			count = 0,
			timer = 0,
			display = makeDisplay(match, pos + Vector3.new(0, 3, 0), Items.TierColors.diamond, 1.6, '<font color="#55FFFF">다이아몬드</font>'),
		})
		table.insert(data.protected, { x, y, z, 1 })
	end
	for _, marker in markers.emerald or {} do
		local x, y, z = cell(marker)
		local pos = world:cellCenter(x, y, z)
		table.insert(data.gens, {
			kind = "emerald",
			pos = pos,
			count = 0,
			timer = 0,
			display = makeDisplay(match, pos + Vector3.new(0, 3, 0), Color3.fromRGB(40, 210, 90), 1.6, '<font color="#55FF55">에메랄드</font>'),
		})
		table.insert(data.protected, { x, y, z, 1 })
	end

	for player in data.players do
		spawnPlayer(match, player)
	end
	Session.title(match.players, '<font color="#FF5555">베드워즈</font>', "침대를 지키고 상대 침대를 부수세요!", 3)
	Session.chat(match.players, '<font color="#55FF55">━━━━━━━━ 베드워즈 ━━━━━━━━</font>')
	Session.chat(match.players, "섬의 생성기에서 철과 금을 모아 상점(F)에서 장비를 사세요.")
	Session.chat(match.players, "다이아몬드로는 팀 강화를, 에메랄드로는 최고급 장비를 살 수 있어요.")
	Session.chat(match.players, "침대가 남아 있으면 죽어도 부활합니다. 상대 침대를 부수고 끝까지 살아남으세요!")
	aliveTeams(match)
end

local function giveResource(player: Player, id: string, amount: number): number
	local state = Session.get(player)
	if not state or amount <= 0 then
		return amount
	end
	local left = Inventory.add(state.inv, id, amount)
	if left < amount then
		Net.send(player, "sound", "pickup")
	end
	return left
end

local function applyEvent(match, event)
	local data = match.data
	if event.kind == "diamond" then
		data.diamondTier = event.tier
		Session.chat(match.players, string.format('<font color="#55FFFF">다이아몬드 생성기</font>가 %s 단계가 되었습니다!', if event.tier == 2 then "II" else "III"))
	elseif event.kind == "emerald" then
		data.emeraldTier = event.tier
		Session.chat(match.players, string.format('<font color="#55FF55">에메랄드 생성기</font>가 %s 단계가 되었습니다!', if event.tier == 2 then "II" else "III"))
	elseif event.kind == "beds" then
		for _, team in data.teamOrder do
			if team.bed then
				destroyBed(match, team.id, nil)
			end
		end
		Session.title(match.players, '<font color="#FF5555">모든 침대 파괴!</font>', "이제 아무도 부활할 수 없습니다", 3)
	elseif event.kind == "end" then
		local alive = aliveTeams(match)
		table.sort(alive, function(a, b)
			return a.alive > b.alive
		end)
		local winners = {}
		local headline = "시간 초과! 무승부입니다."
		if alive[1] and (not alive[2] or alive[1].alive > alive[2].alive) then
			for player in alive[1].members do
				table.insert(winners, player)
			end
			headline = string.format('시간 초과! 가장 많이 살아남은 <font color="%s">%s 팀</font> 승리!', alive[1].def.hex, alive[1].def.name)
		end
		Matches.finish(match, winners, headline)
	end
end

function BedWars.tick(match, dt: number)
	local data = match.data
	data.elapsed += dt
	local event = BW.Events[data.nextEvent]
	while event and data.elapsed >= event.time do
		data.nextEvent += 1
		applyEvent(match, event)
		if match.state ~= "playing" then
			return
		end
		event = BW.Events[data.nextEvent]
	end

	for _, gen in data.gens do
		if gen.kind == "island" then
			local team = data.teams[gen.team]
			local mult = 1 + 0.5 * (if team then team.upgrades.forge else 0)
			gen.ironTimer += dt * mult
			gen.goldTimer += dt * mult
			if gen.ironTimer >= BW.IronInterval then
				gen.ironTimer -= BW.IronInterval
				gen.iron = math.min(BW.IronCap, gen.iron + 1)
			end
			if gen.goldTimer >= BW.GoldInterval then
				gen.goldTimer -= BW.GoldInterval
				gen.gold = math.min(BW.GoldCap, gen.gold + 1)
			end
		else
			local intervals = if gen.kind == "diamond" then BW.DiamondIntervals else BW.EmeraldIntervals
			local tier = if gen.kind == "diamond" then data.diamondTier else data.emeraldTier
			local cap = if gen.kind == "diamond" then BW.DiamondCap else BW.EmeraldCap
			gen.timer += dt
			if gen.timer >= intervals[tier] then
				gen.timer = 0
				gen.count = math.min(cap, gen.count + 1)
			end
		end
	end

	-- 생성기 근처에 있으면 자동으로 줍기
	for player, pd in data.players do
		local state = Session.get(player)
		local root = state and state.root
		if state and root and state.alive and not pd.eliminated and not state.waitingRespawn and state.location == "match" then
			local pos = root.Position
			for _, gen in data.gens do
				local offset = pos - gen.pos
				if math.abs(offset.Y) < 7 and Vector3.new(offset.X, 0, offset.Z).Magnitude < 4.6 then
					if gen.kind == "island" then
						gen.iron = giveResource(player, "iron", gen.iron)
						gen.gold = giveResource(player, "gold", gen.gold)
					else
						gen.count = giveResource(player, gen.kind, gen.count)
					end
				end
			end
		end
	end

	data.displayTimer += dt
	if data.displayTimer >= 0.5 then
		data.displayTimer = 0
		for _, gen in data.gens do
			if gen.kind == "island" then
				gen.display.info.Text = string.format('<font color="#FFFFFF">철 %d</font>  <font color="#FFAA00">금 %d</font>', gen.iron, gen.gold)
			else
				local intervals = if gen.kind == "diamond" then BW.DiamondIntervals else BW.EmeraldIntervals
				local tier = if gen.kind == "diamond" then data.diamondTier else data.emeraldTier
				local left = math.max(0, math.ceil(intervals[tier] - gen.timer))
				local roman = ({ "I", "II", "III" })[tier]
				gen.display.info.Text = string.format("%s단계 · %d개 · %d초", roman, gen.count, left)
			end
		end
	end
end

function BedWars.onDeath(match, player: Player, killer: Player?, cause: string)
	local data = match.data
	local pd = data.players[player]
	if not pd or pd.eliminated then
		Matches.spectate(player)
		return
	end
	local team = data.teams[pd.team]
	local final = not team.bed
	local state = Session.get(player)
	if killer and data.players[killer] and state then
		local killerState = Session.get(killer)
		for id, label in CURRENCY_NAMES do
			local n = Inventory.count(state.inv, id)
			if n > 0 and killerState then
				Inventory.add(killerState.inv, id, n)
				Session.chat({ killer }, string.format('<font color="%s">+%d %s</font>', CURRENCY_HEX[id], n, label))
			end
		end
		local kd = data.players[killer]
		kd.kills += 1
		if final then
			kd.finals += 1
		end
	end
	-- 도구는 한 단계 내려가지만 첫 단계 밑으로는 안 내려감
	if pd.pickaxe > 1 then
		pd.pickaxe -= 1
	end
	if pd.axe > 1 then
		pd.axe -= 1
	end
	Matches.deathMessage(match, player, killer, cause, final)
	if final then
		pd.eliminated = true
		Matches.spectate(player)
		Session.title({ player }, '<font color="#FF5555">탈락!</font>', "침대가 없어서 부활할 수 없습니다", 4)
		checkWin(match)
	else
		Session.setContext(player, { location = "respawn", mode = "bedwars" })
		Matches.respawnLater(player, BW.RespawnTime, function()
			return spawnCFrame(match, pd.team)
		end, function()
			Session.setContext(player, contextFor(match, player))
			giveLoadout(match, player)
		end)
	end
end

function BedWars.onLeave(match, player: Player)
	local pd = match.data.players[player]
	if pd and not pd.eliminated then
		pd.eliminated = true
		Session.chat(match.players, Matches.coloredName(player) .. "님이 게임을 떠났습니다.")
	end
	checkWin(match)
end

function BedWars.canDamage(match, attacker: Player, victim: Player): boolean
	local a, v = match.data.players[attacker], match.data.players[victim]
	return a ~= nil and v ~= nil and a.team ~= v.team and not a.eliminated and not v.eliminated
end

function BedWars.canPlace(match, _player: Player, x: number, y: number, z: number): boolean
	if y > BW.BuildHeight or y < BW.BuildDepth or x * x + z * z > BW.BuildRadius * BW.BuildRadius then
		return false
	end
	for _, p in match.data.protected do
		local dy = y - p[2]
		if math.abs(x - p[1]) <= p[4] and math.abs(z - p[3]) <= p[4] and dy >= 0 and dy <= 2 then
			return false
		end
	end
	return true
end

function BedWars.canBreak(match, player: Player, record): boolean
	local def = Blocks.get(record.id)
	if def and def.bed then
		local pd = match.data.players[player]
		return pd ~= nil and record.data ~= nil and record.data.team ~= pd.team
	end
	return record.placed
end

function BedWars.onBreak(match, player: Player, record)
	local def = Blocks.get(record.id)
	if def and def.bed and record.data then
		destroyBed(match, record.data.team, player)
	end
end

function BedWars.canExplode(_match, record): boolean
	local def = Blocks.get(record.id)
	return record.placed and def ~= nil and not def.blastProof
end

function BedWars.protection(match, player: Player): number
	local team = teamOf(match, player)
	return if team then team.upgrades.protection else 0
end

function BedWars.sharpness(match, player: Player): number
	local team = teamOf(match, player)
	return if team then team.upgrades.sharpness else 0
end

function BedWars.regenInterval(match, player: Player): number
	local team = teamOf(match, player)
	local state = Session.get(player)
	if team and team.upgrades.healpool > 0 and team.marker and state and state.root then
		local x, y, z = cell(team.marker.center)
		local center = match.world:cellCenter(x, y, z)
		if (state.root.Position - center).Magnitude < 12 * B then
			return 0.8
		end
	end
	return Config.Combat.RegenInterval
end

local function pay(player: Player, inv, cost): boolean
	if Inventory.count(inv, cost.item) < cost.amount then
		local def = Items.get(cost.item)
		Session.chat({ player }, string.format('<font color="#FF5555">%s이(가) 부족합니다! (%d개 필요)</font>', if def then def.name else cost.item, cost.amount))
		Net.send(player, "sound", "deny")
		return false
	end
	Inventory.remove(inv, cost.item, cost.amount)
	return true
end

local function shopState(match, player: Player)
	local pd = match.data.players[player]
	local team = teamOf(match, player)
	return {
		team = if pd then pd.team else "red",
		armor = if pd then pd.armor else "leather",
		pickaxe = if pd then pd.pickaxe else 0,
		axe = if pd then pd.axe else 0,
		shears = if pd then pd.shears else false,
		upgrades = if team then team.upgrades else {},
	}
end

function BedWars.openShop(match, player: Player, kind: string)
	local state = Session.get(player)
	if not state or state.match ~= match or state.location ~= "match" or not state.alive then
		return
	end
	Net.send(player, "openShop", kind, shopState(match, player))
end

function BedWars.buy(match, player: Player, key: string)
	local state = Session.get(player)
	local pd = match.data.players[player]
	local item = Shop.getItem(key)
	if not state or not pd or not item or state.location ~= "match" or not state.alive then
		return
	end
	local inv = state.inv
	if item.tiers and item.toolKind then
		local current = pd[item.toolKind]
		local nextTier = item.tiers[current + 1]
		if not nextTier then
			Session.chat({ player }, '<font color="#FF5555">이미 최고 단계입니다.</font>')
			return
		end
		if not pay(player, inv, nextTier.cost) then
			return
		end
		pd[item.toolKind] = current + 1
		Inventory.replaceKind(inv, item.toolKind, nextTier.give)
	elseif item.armor then
		if ARMOR_ORDER[item.armor] <= ARMOR_ORDER[pd.armor] then
			Session.chat({ player }, '<font color="#FF5555">이미 같거나 더 좋은 갑옷이 있습니다.</font>')
			return
		end
		if not item.cost or not pay(player, inv, item.cost) then
			return
		end
		pd.armor = item.armor
		inv.armor.leggings = item.armor .. "_leggings"
		inv.armor.boots = item.armor .. "_boots"
		inv.dirty = true
	elseif item.permanent then
		if pd.shears then
			Session.chat({ player }, '<font color="#FF5555">이미 가지고 있습니다.</font>')
			return
		end
		if not item.cost or not pay(player, inv, item.cost) then
			return
		end
		pd.shears = true
		Inventory.add(inv, item.give or "shears", 1)
	else
		local id = Shop.resolve(item.give or "", pd.team)
		local count = item.count or 1
		if not Items.get(id) then
			return
		end
		if not Inventory.canFit(inv, id, count) then
			Session.chat({ player }, '<font color="#FF5555">가방이 가득 찼습니다!</font>')
			return
		end
		if not item.cost or not pay(player, inv, item.cost) then
			return
		end
		if item.sword then
			Inventory.remove(inv, "wood_sword", Inventory.count(inv, "wood_sword"))
		end
		Inventory.add(inv, id, count)
	end
	Net.send(player, "sound", "buy")
	Net.send(player, "shopState", shopState(match, player))
end

function BedWars.upgrade(match, player: Player, key: string)
	local state = Session.get(player)
	local team = teamOf(match, player)
	local def = Shop.getUpgrade(key)
	if not state or not team or not def or state.location ~= "match" then
		return
	end
	local level = team.upgrades[key] or 0
	local cost = def.costs[level + 1]
	if not cost then
		Session.chat({ player }, '<font color="#FF5555">이미 최고 단계입니다.</font>')
		return
	end
	if not pay(player, state.inv, { item = "diamond", amount = cost }) then
		return
	end
	team.upgrades[key] = level + 1
	local members = {}
	for member in team.members do
		table.insert(members, member)
	end
	Session.chat(members, string.format('%s님이 <font color="#55FFFF">%s %d단계</font>를 구매했습니다!', Matches.coloredName(player), def.name, level + 1))
	Net.sendMany(members, "sound", "buy")
	for _, member in members do
		Net.send(member, "shopState", shopState(match, member))
	end
end

function BedWars.sidebar(match, player: Player)
	local data = match.data
	aliveTeams(match)
	local lines = { "" }
	local event = BW.Events[data.nextEvent]
	if event then
		table.insert(lines, string.format('%s: <font color="#55FF55">%s</font>', event.name, Util.formatTime(event.time - data.elapsed)))
	end
	table.insert(lines, "")
	local mine = match.data.players[player]
	for _, team in data.teamOrder do
		local status
		if team.bed then
			status = '<font color="#55FF55">●</font>'
		elseif team.alive > 0 then
			status = string.format('<font color="#FFFF55">%d</font>', team.alive)
		else
			status = '<font color="#FF5555">×</font>'
		end
		local you = if mine and mine.team == team.id then ' <font color="#AAAAAA">◀ 나</font>' else ""
		table.insert(lines, string.format('<font color="%s">%s</font> %s: %s%s', team.def.hex, team.def.letter, team.def.name, status, you))
	end
	table.insert(lines, "")
	if mine then
		table.insert(lines, string.format('처치: <font color="#55FF55">%d</font>', mine.kills))
		table.insert(lines, string.format('최종 처치: <font color="#55FF55">%d</font>', mine.finals))
		table.insert(lines, string.format('침대 파괴: <font color="#55FF55">%d</font>', mine.beds))
		table.insert(lines, "")
	end
	table.insert(lines, '<font color="#AAAAAA">맵: ' .. (if match.map then match.map.name else "") .. "</font>")
	return { title = '<font color="#FFFF55">베드워즈</font>', lines = lines }
end

function BedWars.cleanup(match)
	match.data = {}
end

return BedWars
