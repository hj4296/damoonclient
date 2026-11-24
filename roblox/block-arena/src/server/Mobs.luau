-- 몹: 베드워즈 수호 골렘/좀벌레, 서든데스 용, 럭키 블록 좀벌레.
-- 물리 대신 서버가 블록 격자를 보고 직접 움직입니다(걷기, 한 칸 오르기, 떨어지기). 용은 날아다니며 놓인 블록을 부숩니다.
-- 몹 파트에는 "Mob" 태그와 "MobId" 속성이 있어서 클라이언트가 조준하고 "attackMob" 으로 때립니다.
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Rules = require(Shared.Rules)
local Session = require(script.Parent.Session)
local Combat = require(script.Parent.Combat)
local Net = require(script.Parent.Net)
local Explosion = require(script.Parent.Explosion)
local Projectiles = require(script.Parent.Projectiles)
local MobMotion = require(script.Parent.Logic.MobMotion)

local B = Config.BlockSize

local Mobs = {}

export type Mob = {
	id: number,
	kind: string,
	def: { [string]: any },
	match: any,
	team: string?, -- 이 팀은 공격하지 않음 (nil 이면 owner 빼고 모두 공격)
	owner: Player?,
	model: Model,
	parts: { { part: BasePart, offset: CFrame, swing: number?, pivot: Vector3? } },
	feet: Vector3, -- 땅 몹: 발 위치 / 용: 몸 가운데
	yaw: number,
	velocity: Vector3,
	health: number,
	maxHealth: number,
	expires: number,
	lastAttack: number,
	walkPhase: number,
	target: Player?,
	retarget: number,
	wander: Vector3?,
	bar: Frame?,
	flying: boolean,
	moving: boolean,
	dead: boolean,
}

local mobs: { [number]: Mob } = {}
local byPart: { [BasePart]: Mob } = {}
local nextId = 0

local function rgb(r: number, g: number, b: number): Color3
	return Color3.fromRGB(r, g, b)
end

-- 몸 조각: 이름, 크기, 발 기준 위치, 색, 흔들리는 다리면 swing 방향
type Piece = { any } -- { 이름, 크기, 위치, 색, 흔들기 방향? }

local function teamColor(teamId: string?): Color3
	for _, team in Config.Teams do
		if team.id == teamId then
			return team.color
		end
	end
	return rgb(200, 200, 200)
end

local function shapeFor(kind: string, teamId: string?): ({ Piece }, number)
	if kind == "golem" then
		local body = rgb(214, 210, 198)
		local vine = rgb(80, 128, 50)
		local band = teamColor(teamId)
		return {
			{ "LegL", Vector3.new(1.1, 2.6, 1.1), Vector3.new(-0.7, 1.3, 0), body, 1 },
			{ "LegR", Vector3.new(1.1, 2.6, 1.1), Vector3.new(0.7, 1.3, 0), body, -1 },
			{ "Body", Vector3.new(3, 2.8, 1.8), Vector3.new(0, 4, 0), body },
			{ "Band", Vector3.new(3.05, 0.5, 1.85), Vector3.new(0, 3.2, 0), band },
			{ "Vine", Vector3.new(0.4, 1.6, 1.85), Vector3.new(0.7, 4.3, 0), vine },
			{ "ArmL", Vector3.new(0.9, 3.6, 0.9), Vector3.new(-1.95, 3.6, 0), body, -1 },
			{ "ArmR", Vector3.new(0.9, 3.6, 0.9), Vector3.new(1.95, 3.6, 0), body, 1 },
			{ "Head", Vector3.new(1.4, 1.5, 1.4), Vector3.new(0, 6.15, -0.2), body },
			{ "Nose", Vector3.new(0.35, 0.7, 0.35), Vector3.new(0, 5.9, -1), rgb(190, 176, 160) },
			{ "Eyes", Vector3.new(1, 0.22, 0.1), Vector3.new(0, 6.35, -0.92), rgb(150, 30, 30) },
		}, 7
	elseif kind == "silverfish" then
		local shell = rgb(150, 150, 156)
		local dark = rgb(110, 110, 118)
		return {
			{ "Head", Vector3.new(0.7, 0.5, 0.6), Vector3.new(0, 0.25, -0.9), dark },
			{ "Seg1", Vector3.new(0.9, 0.65, 0.6), Vector3.new(0, 0.33, -0.3), shell, 1 },
			{ "Seg2", Vector3.new(0.8, 0.55, 0.6), Vector3.new(0, 0.28, 0.3), shell, -1 },
			{ "Tail", Vector3.new(0.5, 0.4, 0.5), Vector3.new(0, 0.2, 0.85), dark, 1 },
		}, 0.7
	else
		-- 용: 날개 달린 큰 몸 (팀 색 비늘)
		local scale = rgb(34, 30, 40)
		local accent = teamColor(teamId)
		return {
			{ "Body", Vector3.new(3.4, 2.6, 7), Vector3.new(0, 0, 0), scale },
			{ "Ridge", Vector3.new(0.6, 0.8, 6), Vector3.new(0, 1.6, 0.4), accent },
			{ "Neck", Vector3.new(1.6, 1.6, 3), Vector3.new(0, 0.6, -4.8), scale },
			{ "Head", Vector3.new(2.4, 2, 3), Vector3.new(0, 0.9, -7.4), scale },
			{ "Eyes", Vector3.new(2.45, 0.35, 0.5), Vector3.new(0, 1.4, -8), rgb(200, 60, 255) },
			{ "Tail", Vector3.new(1.4, 1.2, 6), Vector3.new(0, 0.2, 6.4), scale },
			{ "WingL", Vector3.new(8, 0.3, 4.5), Vector3.new(-5.6, 1, -0.5), accent:Lerp(scale, 0.55), 1 },
			{ "WingR", Vector3.new(8, 0.3, 4.5), Vector3.new(5.6, 1, -0.5), accent:Lerp(scale, 0.55), -1 },
		}, 3
	end
end

local function makeBar(model: Model, adornee: BasePart, name: string, height: number, color: Color3): Frame
	local gui = Instance.new("BillboardGui")
	gui.Name = "MobBar"
	gui.Size = UDim2.new(5, 0, 1.1, 0)
	gui.StudsOffset = Vector3.new(0, height, 0)
	gui.MaxDistance = 80
	gui.LightInfluence = 0
	gui.Adornee = adornee
	local label = Instance.new("TextLabel")
	label.BackgroundTransparency = 1
	label.Size = UDim2.new(1, 0, 0.6, 0)
	label.Font = Enum.Font.Arcade
	label.TextScaled = true
	label.TextColor3 = color
	label.TextStrokeTransparency = 0.3
	label.Text = name
	label.Parent = gui
	local back = Instance.new("Frame")
	back.BackgroundColor3 = Color3.new(0, 0, 0)
	back.BackgroundTransparency = 0.3
	back.BorderSizePixel = 0
	back.Position = UDim2.new(0.1, 0, 0.66, 0)
	back.Size = UDim2.new(0.8, 0, 0.22, 0)
	back.Parent = gui
	local fill = Instance.new("Frame")
	fill.Name = "Fill"
	fill.BackgroundColor3 = Color3.fromRGB(230, 50, 50)
	fill.BorderSizePixel = 0
	fill.Size = UDim2.new(1, 0, 1, 0)
	fill.Parent = back
	gui.Parent = model
	return fill
end

-- 땅을 찾아 발 위치 맞추기: 위에서부터 내려가며 단단한 블록 위
local function groundY(world, x: number, y: number, z: number): number?
	for dy = 0, -6, -1 do
		if world:isSolid(x, y + dy - 1, z) and not world:isSolid(x, y + dy, z) then
			return y + dy
		end
	end
	return nil
end

function Mobs.spawn(match, kind: string, position: Vector3, opts: { team: string?, owner: Player?, life: number? }): Mob?
	local def = Config.Mobs[kind]
	if not def or not match.world or match.state ~= "playing" then
		return nil
	end
	nextId += 1
	local id = nextId
	local pieces, height = shapeFor(kind, opts.team)
	local model = Instance.new("Model")
	model.Name = "Mob_" .. kind
	model:AddTag("Mob")
	model:SetAttribute("MobId", id)
	model:SetAttribute("MobKind", kind)
	model:SetAttribute("Team", opts.team)
	local mob: Mob = {
		id = id,
		kind = kind,
		def = def,
		match = match,
		team = opts.team,
		owner = opts.owner,
		model = model,
		parts = {},
		feet = position,
		yaw = 0,
		velocity = Vector3.zero,
		health = def.health,
		maxHealth = def.health,
		expires = os.clock() + (opts.life or 1e9),
		lastAttack = 0,
		walkPhase = 0,
		target = nil,
		retarget = 0,
		wander = nil,
		bar = nil,
		flying = kind == "dragon",
		moving = false,
		dead = false,
	}
	local first: BasePart? = nil
	for _, piece in pieces do
		local part = Instance.new("Part")
		part.Name = piece[1]
		part.Anchored = true
		part.CanCollide = false
		part.CanTouch = false
		part.CanQuery = true
		part.CastShadow = kind ~= "silverfish"
		part.Size = piece[2]
		part.Color = piece[4]
		part.Material = Enum.Material.SmoothPlastic
		part.TopSurface = Enum.SurfaceType.Smooth
		part.BottomSurface = Enum.SurfaceType.Smooth
		part:AddTag("Mob")
		part:SetAttribute("MobId", id)
		part.Parent = model
		byPart[part] = mob
		local pivot = if piece[5] then piece[3] + Vector3.new(0, piece[2].Y / 2, 0) else nil
		if kind == "dragon" and piece[5] then
			-- 날개는 몸 쪽 끝을 축으로 펄럭임
			pivot = piece[3] + Vector3.new(-piece[2].X / 2 * (piece[5] :: number), 0, 0)
		end
		table.insert(mob.parts, { part = part, offset = CFrame.new(piece[3]), swing = piece[5], pivot = pivot })
		first = first or part
	end
	local name = def.name
	if opts.owner then
		name = opts.owner.DisplayName .. "의 " .. name
	end
	if first then
		mob.bar = makeBar(model, first, name, height, if opts.team then teamColor(opts.team) else Color3.new(1, 1, 1))
	end
	model.Parent = match.world.extras
	mobs[id] = mob
	return mob
end

local function placeParts(mob: Mob, t: number)
	local root = CFrame.new(mob.feet) * CFrame.Angles(0, mob.yaw, 0)
	local moving = mob.moving
	local parts, cframes = {}, {}
	for _, p in mob.parts do
		local cf = root * p.offset
		if p.swing and p.pivot then
			local angle
			if mob.flying then
				angle = math.sin(t * 5 + mob.id) * 0.45 * p.swing
				local pivot = root * CFrame.new(p.pivot)
				cf = pivot * CFrame.Angles(0, 0, angle) * pivot:Inverse() * cf
			else
				angle = if moving then math.sin(mob.walkPhase) * 0.6 * p.swing else 0
				if mob.kind == "silverfish" then
					cf = cf * CFrame.new(math.sin(mob.walkPhase + p.swing) * 0.12, 0, 0)
				else
					local pivot = root * CFrame.new(p.pivot)
					cf = pivot * CFrame.Angles(angle, 0, 0) * pivot:Inverse() * cf
				end
			end
		end
		table.insert(parts, p.part)
		table.insert(cframes, cf)
	end
	workspace:BulkMoveTo(parts, cframes, Enum.BulkMoveMode.FireCFrameChanged)
end

function Mobs.remove(mob: Mob)
	if mob.dead then
		return
	end
	mob.dead = true
	mobs[mob.id] = nil
	for _, p in mob.parts do
		byPart[p.part] = nil
	end
	mob.model:Destroy()
end

function Mobs.get(id: number): Mob?
	return mobs[id]
end

function Mobs.fromPart(part: BasePart): Mob?
	return byPart[part]
end

function Mobs.clearMatch(match)
	for _, mob in mobs do
		if mob.match == match then
			Mobs.remove(mob)
		end
	end
end

function Mobs.count(match, kind: string, team: string?): number
	local n = 0
	for _, mob in mobs do
		if mob.match == match and mob.kind == kind and (team == nil or mob.team == team) then
			n += 1
		end
	end
	return n
end

-- 이 몹이 그 사람을 공격해도 되는지 (같은 팀/주인은 안 됨)
local function hostileTo(mob: Mob, player: Player): boolean
	local state = Session.get(player)
	if not state or state.match ~= mob.match or state.location ~= "match" or not state.alive or state.waitingRespawn then
		return false
	end
	if player == mob.owner then
		return false
	end
	if mob.team and state.team == mob.team then
		return false
	end
	return true
end

-- 사람이 몹을 때릴 수 있는지
function Mobs.canHit(player: Player, mob: Mob): boolean
	local state = Session.get(player)
	if not state or state.match ~= mob.match or state.location ~= "match" or not state.alive or mob.match.state ~= "playing" then
		return false
	end
	if player == mob.owner then
		return false
	end
	return not (mob.team and state.team == mob.team)
end

function Mobs.damage(mob: Mob, amount: number, source: Player?, push: Vector3?)
	if mob.dead then
		return
	end
	mob.health -= amount
	if mob.bar then
		mob.bar.Size = UDim2.new(math.clamp(mob.health / mob.maxHealth, 0, 1), 0, 1, 0)
	end
	for _, p in mob.parts do
		local part = p.part
		local original = part.Color
		part.Color = original:Lerp(Color3.fromRGB(255, 40, 40), 0.6)
		task.delay(0.2, function()
			if part.Parent then
				part.Color = original
			end
		end)
	end
	if push and not mob.flying then
		mob.velocity += push * (if mob.kind == "golem" then 0.3 else 1)
	end
	if source and mob.health > 0 and mob.kind ~= "dragon" then
		mob.target = source
		mob.retarget = os.clock() + 4
	end
	if mob.health <= 0 then
		if source then
			local name = Config.Mobs[mob.kind].name
			Net.send(source, "chat", string.format('<font color="#AAAAAA">%s을(를) 쓰러뜨렸습니다.</font>', name))
		end
		Mobs.remove(mob)
	end
end

-- 근접 공격으로 몹 때리기
function Mobs.melee(player: Player, mobId: any)
	if type(mobId) ~= "number" then
		return
	end
	local mob = mobs[mobId]
	local state = Session.get(player)
	if not mob or not state or not state.root or not Mobs.canHit(player, mob) then
		return
	end
	local now = os.clock()
	if now - state.lastAttack < 0.25 then
		return
	end
	local center = mob.feet + (if mob.flying then Vector3.zero else Vector3.new(0, 1, 0))
	if (center - state.root.Position).Magnitude > Config.Reach.AttackServer + (if mob.kind == "dragon" then 6 else 2) then
		return
	end
	state.lastAttack = now
	local stack = state.inv.slots[state.inv.selected]
	local held = if stack then stack.id else nil
	local damage = Rules.meleeDamage(held, Combat.getSharpness(player))
	local flat = center - state.root.Position
	flat = Vector3.new(flat.X, 0, flat.Z)
	local push = (if flat.Magnitude > 0.05 then flat.Unit else Vector3.zero) * 14 * Rules.knockbackMultiplier(held) + Vector3.new(0, 8, 0)
	Net.broadcast("swing", player)
	Mobs.damage(mob, damage, player, push)
end

local function nearestTarget(mob: Mob, range: number): Player?
	local best, bestDistance = nil, range
	for _, player in mob.match.players do
		if hostileTo(mob, player) then
			local state = Session.get(player)
			local root = state and state.root
			if root then
				local d = (root.Position - mob.feet).Magnitude
				if d < bestDistance then
					best, bestDistance = player, d
				end
			end
		end
	end
	return best
end

local function attack(mob: Mob, victim: Player, now: number)
	if now - mob.lastAttack < mob.def.attackInterval then
		return
	end
	local state = Session.get(victim)
	local root = state and state.root
	if not root then
		return
	end
	mob.lastAttack = now
	local offset = root.Position - mob.feet
	local flat = Vector3.new(offset.X, 0, offset.Z)
	local dir = if flat.Magnitude > 0.05 then flat.Unit else Vector3.zAxis
	local kb = mob.def.knockback
	local knock = dir * Config.Combat.KnockbackH * kb + Vector3.new(0, Config.Combat.KnockbackV * math.min(kb, 1.3), 0)
	-- 주인이 있으면 처치 기록은 주인에게
	local credit = if mob.owner and Combat.canDamage(mob.owner, victim) then mob.owner else nil
	Combat.damage(victim, mob.def.damage, { attacker = credit, cause = "mob_" .. mob.kind, knockback = knock })
end

local function stepGround(mob: Mob, dt: number, now: number)
	local world = mob.match.world
	local def = mob.def
	if now > mob.retarget or not mob.target or not hostileTo(mob, mob.target) then
		mob.target = nearestTarget(mob, def.range)
		mob.retarget = now + 1
	end
	local wish = Vector3.zero
	local target = mob.target
	local targetState = if target then Session.get(target) else nil
	local targetRoot = targetState and targetState.root
	if targetRoot then
		local offset = targetRoot.Position - mob.feet
		local flat = Vector3.new(offset.X, 0, offset.Z)
		if flat.Magnitude > 1.5 then
			wish = flat.Unit * def.speed
		end
		if offset.Magnitude < def.reach + 1 then
			attack(mob, target :: Player, now)
		end
	end

	local feet, velocity = MobMotion.ground(world, mob.feet, mob.velocity, wish, dt, B)
	local horizontal = Vector3.new(velocity.X, 0, velocity.Z)
	mob.velocity = velocity
	mob.feet = feet
	local look = if wish.Magnitude > 0 then wish else horizontal
	if look.Magnitude > 0.5 then
		mob.yaw = math.atan2(-look.X, -look.Z)
	end
	mob.moving = wish.Magnitude > 0
	if mob.moving then
		mob.walkPhase += dt * (if mob.kind == "silverfish" then 18 else 7)
	end
	if feet.Y < (mob.match.voidY or -1e9) then
		Mobs.remove(mob)
	end
end

-- 용: 가장 가까운 적에게 날아가 들이받고, 지나가는 길의 놓인 블록을 부숨
local function stepDragon(mob: Mob, dt: number, now: number)
	local match = mob.match
	local world = match.world
	if now > mob.retarget or not mob.target or not hostileTo(mob, mob.target) then
		mob.target = nearestTarget(mob, mob.def.range)
		mob.retarget = now + 3
	end
	local goal: Vector3
	local targetState = if mob.target then Session.get(mob.target) else nil
	local targetRoot = targetState and targetState.root
	if targetRoot then
		goal = targetRoot.Position + Vector3.new(0, 2, 0)
	else
		if not mob.wander or (mob.wander - mob.feet).Magnitude < 6 then
			local angle = math.random() * math.pi * 2
			mob.wander = match.origin + Vector3.new(math.cos(angle) * 25 * B, (8 + math.random() * 5) * B, math.sin(angle) * 25 * B)
		end
		goal = mob.wander :: Vector3
	end
	local offset = goal - mob.feet
	local dir = if offset.Magnitude > 0.1 then offset.Unit else Vector3.zAxis
	local speed = mob.def.speed
	mob.velocity = mob.velocity:Lerp(dir * speed, math.min(1, dt * 1.6))
	mob.feet += mob.velocity * dt
	if mob.velocity.Magnitude > 0.5 then
		mob.yaw = math.atan2(-mob.velocity.X, -mob.velocity.Z)
	end
	-- 지나가는 자리의 블록 부수기 (사람이 놓은 블록만)
	local cx, cy, cz = world:toCell(mob.feet)
	for dx = -1, 1 do
		for dy = -1, 1 do
			for dz = -1, 1 do
				local record = world:get(cx + dx, cy + dy, cz + dz)
				if record and record.placed and match.mode.canExplode(match, record) then
					world:remove(record.x, record.y, record.z)
				end
			end
		end
	end
	if targetRoot and (targetRoot.Position - mob.feet).Magnitude < mob.def.reach then
		attack(mob, mob.target :: Player, now)
	end
end

local clock = 0
RunService.Heartbeat:Connect(function(dt)
	clock += dt
	local now = os.clock()
	for _, mob in mobs do
		local ok, err = pcall(function()
			if mob.dead then
				return
			end
			if now > mob.expires or mob.match.state ~= "playing" or not mob.match.world then
				Mobs.remove(mob)
				return
			end
			if mob.flying then
				stepDragon(mob, dt, now)
			else
				stepGround(mob, dt, now)
			end
			if not mob.dead then
				placeParts(mob, clock)
			end
		end)
		if not ok then
			warn("[블록 아레나] 몹 오류:", err)
			Mobs.remove(mob)
		end
	end
end)

-- 땅 몹을 놓을 위치: 그 칸 위의 바닥
function Mobs.groundAt(match, x: number, y: number, z: number): Vector3?
	local world = match.world
	local gy = groundY(world, x, y, z)
	if not gy then
		return nil
	end
	local center = world:cellCenter(x, gy, z)
	return center - Vector3.new(0, B / 2, 0)
end

-- 폭발과 화살에 몹도 다치게 연결
Explosion.hurtMobs = function(match, position: Vector3, reach: number, power: number, source: Player?)
	for _, mob in mobs do
		if mob.match == match and not mob.dead then
			local center = mob.feet + (if mob.flying then Vector3.zero else Vector3.new(0, 1, 0))
			local distance = (center - position).Magnitude
			if distance <= reach and (not source or Mobs.canHit(source, mob)) then
				local falloff = 1 - distance / reach
				local dir = if distance > 0.1 then (center - position).Unit else Vector3.yAxis
				Mobs.damage(mob, math.max(0.5, power * falloff), source, dir * 30 * falloff + Vector3.new(0, 18 * falloff, 0))
			end
		end
	end
end
Projectiles.mobFromPart = function(part: BasePart): any
	return byPart[part]
end

return Mobs
