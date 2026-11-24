-- 플레이어별 상태(어디에 있는지, 가방, 팀, 마지막으로 때린 사람 등)와 캐릭터 생성/꾸미기
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Items = require(Shared.Items)
local Skins = require(Shared.Skins)
local CharacterRig = require(Shared.CharacterRig)
local Preferences = require(Shared.Preferences)
local Inventory = require(script.Parent.Logic.Inventory)
local Net = require(script.Parent.Net)

local Session = {}

export type State = {
	player: Player,
	inv: Inventory.Inv,
	location: string, -- lobby, waiting, match, spectate
	match: any,
	team: string?,
	alive: boolean,
	character: Model?,
	humanoid: Humanoid?,
	root: BasePart?,
	lastHit: { by: Player?, cause: string, time: number },
	hurtUntil: number,
	lastAttack: number,
	lastDamaged: number,
	absorption: number,
	absorptionUntil: number,
	pendingSpawn: CFrame?,
	onSpawn: ((Model) -> ())?,
	spawnToken: number,
	container: any,
	breaking: { key: number, time: number }?,
	heldId: string?,
	context: { [string]: any },
	stats: { wins: number, kills: number },
	lastUse: number,
	deathCause: string?,
	respawnToken: any,
	waitingRespawn: boolean,
	skin: string, -- 스킨 코드 (Skins.encode)
	savedSkin: string?, -- 저장해 둔 커스텀 스킨 (패스가 없으면 입지는 못하고 보관만)
	settings: { [string]: any },
	passes: { [string]: boolean },
	armorKey: string,
}

local states: { [Player]: State } = {}

-- Matches 가 채워 넣는 콜백
Session.onDied = function(_player: Player, _state: State) end
Session.onSpawned = function(_player: Player, _state: State, _character: Model) end

local NEUTRAL = Color3.fromRGB(235, 235, 235)

function Session.create(player: Player): State
	local state: State = {
		player = player,
		inv = Inventory.new(),
		location = "lobby",
		match = nil,
		team = nil,
		alive = false,
		character = nil,
		humanoid = nil,
		root = nil,
		lastHit = { by = nil, cause = "", time = 0 },
		hurtUntil = 0,
		lastAttack = 0,
		lastDamaged = 0,
		absorption = 0,
		absorptionUntil = 0,
		pendingSpawn = nil,
		onSpawn = nil,
		spawnToken = 0,
		container = nil,
		breaking = nil,
		heldId = nil,
		context = {},
		stats = { wins = 0, kills = 0 },
		lastUse = 0,
		deathCause = nil,
		respawnToken = nil,
		waitingRespawn = false,
		skin = Skins.DefaultCode,
		savedSkin = nil,
		settings = Preferences.defaults(),
		passes = {},
		armorKey = "",
	}
	states[player] = state
	player:SetAttribute("Skin", state.skin)
	return state
end

function Session.get(player: Player): State?
	return states[player]
end

function Session.remove(player: Player)
	states[player] = nil
end

function Session.all(): { [Player]: State }
	return states
end

function Session.teamColor(state: State): Color3
	if state.team then
		for _, team in Config.Teams do
			if team.id == state.team then
				return team.color
			end
		end
	end
	return NEUTRAL
end

function Session.isVip(player: Player): boolean
	local state = states[player]
	return state ~= nil and state.passes.vip == true
end

-- 다리 길이 (R6 블록 캐릭터는 HipHeight 가 0 이고 다리가 2스터드)
function Session.legLength(humanoid: Humanoid): number
	if humanoid.RigType == Enum.HumanoidRigType.R6 then
		return 2
	end
	return humanoid.HipHeight
end

-- 텍스처가 없을 때도 보이는 기본 색 (클라이언트가 그 위에 픽셀 스킨을 입힘)
local function colorCharacter(character: Model, skinCode: string)
	local skin = Skins.decode(skinCode)
	for _, name in CharacterRig.BodyParts do
		local part = character:FindFirstChild(name)
		if part and part:IsA("BasePart") then
			part.Color = Skins.partColor(skin, name)
		end
	end
end

-- 머리 위 이름표: 팀 색 이름, VIP 는 앞에 금색 [VIP]
local function nametag(character: Model, player: Player, color: Color3, vip: boolean)
	local head = character:FindFirstChild("Head")
	if not head then
		return
	end
	local old = head:FindFirstChild("Nametag")
	if old then
		old:Destroy()
	end
	local gui = Instance.new("BillboardGui")
	gui.Name = "Nametag"
	gui.Size = UDim2.new(if vip then 8 else 6, 0, 1, 0)
	gui.StudsOffset = Vector3.new(0, 2.2, 0)
	gui.MaxDistance = 70
	gui.LightInfluence = 0
	local label = Instance.new("TextLabel")
	label.BackgroundTransparency = 0.6
	label.BackgroundColor3 = Color3.new(0, 0, 0)
	label.BorderSizePixel = 0
	label.Size = UDim2.new(1, 0, 1, 0)
	label.Font = Enum.Font.Arcade
	label.TextScaled = true
	label.RichText = true
	label.TextColor3 = color
	label.Text = (if vip then '<font color="#FFD24A">[VIP]</font> ' else "") .. player.DisplayName
	label.Parent = gui
	gui.Parent = head
end

function Session.refreshNametag(player: Player)
	local state = states[player]
	if state and state.character then
		nametag(state.character, player, if state.team then Session.teamColor(state) else Color3.new(1, 1, 1), state.passes.vip == true)
	end
end

-- 스킨 바꾸기 (검사는 부르는 쪽에서)
function Session.setSkin(player: Player, code: string)
	local state = states[player]
	if not state then
		return
	end
	state.skin = Skins.encode(Skins.decode(code))
	player:SetAttribute("Skin", state.skin)
	if state.character then
		colorCharacter(state.character, state.skin)
	end
end

-- 입은 갑옷을 다른 사람에게도 보이게 캐릭터 속성으로 적어 둠 (예: "helmet=leather:FF5555;chestplate=iron")
-- 베드워즈 가죽 갑옷은 팀 색으로 물들임
function Session.refreshArmor(state: State)
	local character = state.character
	if not character then
		return
	end
	local dye = if state.team then Session.teamColor(state):ToHex() else nil
	local parts = {}
	for _, slot in Items.ArmorSlots do
		local id = state.inv.armor[slot]
		local def = if id then Items.get(id) else nil
		if def and def.armorTier then
			local entry = slot .. "=" .. def.armorTier
			if def.armorTier == "leather" and dye then
				entry ..= ":" .. dye
			end
			table.insert(parts, entry)
		end
	end
	local key = table.concat(parts, ";")
	if key ~= state.armorKey or character:GetAttribute("Armor") ~= key then
		state.armorKey = key
		character:SetAttribute("Armor", key)
	end
end

-- 들고 있는 아이템을 오른손에 보이게 붙입니다.
function Session.refreshHeld(state: State)
	local character = state.character
	if not character then
		return
	end
	local stack = state.inv.slots[state.inv.selected]
	local id = if stack then stack.id else nil
	local hasPart = character:FindFirstChild("HeldItem") ~= nil
	if id == state.heldId and hasPart == (id ~= nil) then
		return
	end
	state.heldId = id
	local old = character:FindFirstChild("HeldItem")
	if old then
		old:Destroy()
	end
	if not id then
		return
	end
	local def = Items.get(id)
	local hand = character:FindFirstChild("RightHand") or character:FindFirstChild("Right Arm")
	if not def or not hand or not hand:IsA("BasePart") then
		return
	end
	local part = Instance.new("Part")
	part.Name = "HeldItem"
	part.CanCollide = false
	part.CanQuery = false
	part.CanTouch = false
	part.Massless = true
	part.Color = def.color
	part.TopSurface = Enum.SurfaceType.Smooth
	part.BottomSurface = Enum.SurfaceType.Smooth
	local offset = CFrame.new(0, -hand.Size.Y / 2, -0.4)
	if def.kind == "block" then
		part.Size = Vector3.new(0.9, 0.9, 0.9)
		part.Material = Enum.Material.SmoothPlastic
		offset *= CFrame.new(0, -0.2, -0.3)
	elseif def.kind == "sword" or def.kind == "pickaxe" or def.kind == "axe" or def.kind == "bow" then
		part.Size = Vector3.new(0.25, 0.35, 3)
		part.Material = if def.kind == "bow" then Enum.Material.Wood else Enum.Material.Metal
		offset *= CFrame.new(0, 0, -1.2)
	else
		part.Size = Vector3.new(0.6, 0.6, 0.6)
		part.Material = Enum.Material.SmoothPlastic
	end
	local weld = Instance.new("Weld")
	weld.Part0 = hand
	weld.Part1 = part
	weld.C0 = offset
	weld.Parent = part
	part.Parent = character
end

-- 캐릭터를 원하는 위치에 새로 만듭니다(죽었다 살아나기, 이동 모두 이걸로).
function Session.spawn(player: Player, cframe: CFrame, onSpawn: ((Model) -> ())?)
	local state = states[player]
	if not state then
		return
	end
	state.pendingSpawn = cframe
	state.onSpawn = onSpawn
	state.spawnToken += 1
	local token = state.spawnToken
	task.spawn(function()
		if player.Parent and state.spawnToken == token then
			player:LoadCharacter()
		end
	end)
end

-- 캐릭터를 다시 만들지 않고 순간이동만
function Session.teleport(player: Player, cframe: CFrame)
	local character = player.Character
	if character then
		character:PivotTo(cframe)
	end
end

local function onCharacterAdded(player: Player, character: Model)
	local state = states[player]
	if not state then
		return
	end
	local humanoid = character:WaitForChild("Humanoid", 10) :: Humanoid?
	local root = character:WaitForChild("HumanoidRootPart", 10) :: BasePart?
	if not humanoid or not root then
		return
	end
	local started = os.clock()
	while not character:IsDescendantOf(workspace) and os.clock() - started < 5 do
		task.wait()
	end
	task.wait()
	if player.Character ~= character then
		return
	end

	state.character = character
	state.humanoid = humanoid
	state.root = root
	state.alive = true
	state.heldId = nil
	state.hurtUntil = 0
	state.lastHit = { by = nil, cause = "", time = 0 }
	state.deathCause = nil

	humanoid.MaxHealth = 20
	humanoid.Health = 20
	humanoid.DisplayDistanceType = Enum.HumanoidDisplayDistanceType.None
	humanoid.BreakJointsOnDeath = true
	humanoid.WalkSpeed = Config.Movement.Walk
	humanoid.UseJumpPower = false
	humanoid.JumpHeight = Config.Movement.JumpHeight

	if state.pendingSpawn then
		character:PivotTo(state.pendingSpawn)
		root.AssemblyLinearVelocity = Vector3.zero
	end

	colorCharacter(character, state.skin)
	nametag(character, player, if state.team then Session.teamColor(state) else Color3.new(1, 1, 1), state.passes.vip == true)
	state.armorKey = ""
	Session.refreshHeld(state)
	Session.refreshArmor(state)

	humanoid.Died:Connect(function()
		if state.character ~= character then
			return
		end
		state.alive = false
		Session.onDied(player, state)
	end)

	local callback = state.onSpawn
	state.onSpawn = nil
	if callback then
		callback(character)
	end
	Session.onSpawned(player, state, character)
end

function Session.attach(player: Player)
	player.CharacterAdded:Connect(function(character)
		onCharacterAdded(player, character)
	end)
end

function Session.setContext(player: Player, context: { [string]: any })
	local state = states[player]
	if state then
		state.context = context
	end
	Net.send(player, "context", context)
end

function Session.title(players: { Player }, title: string, subtitle: string?, duration: number?)
	Net.sendMany(players, "title", title, subtitle or "", duration or 2.5)
end

function Session.chat(players: { Player }, text: string)
	Net.sendMany(players, "chat", text)
end

-- 가방이 바뀐 사람에게만 한 프레임에 한 번 보냅니다.
RunService.Heartbeat:Connect(function()
	for player, state in states do
		if state.inv.dirty then
			state.inv.dirty = false
			Net.send(player, "inv", Inventory.serialize(state.inv))
			Session.refreshHeld(state)
			Session.refreshArmor(state)
		end
	end
end)

return Session
