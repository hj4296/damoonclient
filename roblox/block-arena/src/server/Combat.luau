-- 전투: 피해(갑옷/보호막 적용), 넉백, 근접 공격 판정, 자연 회복
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Rules = require(Shared.Rules)
local Session = require(script.Parent.Session)
local Net = require(script.Parent.Net)

local Combat = {}

-- 게임 모드가 바꿔 끼우는 규칙
Combat.canDamage = function(_attacker: Player, _victim: Player): boolean
	return false
end
Combat.getProtection = function(_player: Player): number
	return 0
end
Combat.getSharpness = function(_player: Player): number
	return 0
end
Combat.getRegenInterval = function(_player: Player): number
	return Config.Combat.RegenInterval
end

local function setAbsorptionAttribute(player: Player, amount: number)
	player:SetAttribute("Absorption", amount)
end

local function flash(character: Model)
	local highlight = character:FindFirstChild("HurtFlash")
	if not highlight then
		local h = Instance.new("Highlight")
		h.Name = "HurtFlash"
		h.FillColor = Color3.fromRGB(255, 40, 40)
		h.FillTransparency = 0.45
		h.OutlineTransparency = 1
		h.DepthMode = Enum.HighlightDepthMode.Occluded
		h.Enabled = false
		h.Parent = character
		highlight = h
	end
	if highlight and highlight:IsA("Highlight") then
		highlight.Enabled = true
		task.delay(0.25, function()
			if highlight.Parent then
				highlight.Enabled = false
			end
		end)
	end
end

local function hitSound(root: BasePart)
	local sound = root:FindFirstChild("HitSound")
	if not sound then
		local s = Instance.new("Sound")
		s.Name = "HitSound"
		s.SoundId = Config.Sounds.Hit
		s.Volume = 0.5
		s.RollOffMaxDistance = 60
		s.Parent = root
		sound = s
	end
	if sound and sound:IsA("Sound") then
		sound:Play()
	end
end

function Combat.knockback(player: Player, velocity: Vector3)
	Net.send(player, "kb", velocity)
end

export type DamageOptions = {
	attacker: Player?,
	cause: string,
	ignoreArmor: boolean?,
	knockback: Vector3?,
}

function Combat.damage(victim: Player, amount: number, opts: DamageOptions): boolean
	local state = Session.get(victim)
	if not state or not state.alive or not state.humanoid then
		return false
	end
	local humanoid = state.humanoid
	if humanoid.Health <= 0 then
		return false
	end
	local now = os.clock()
	if state.absorption > 0 and now > state.absorptionUntil then
		state.absorption = 0
	end
	local health, absorption = Rules.applyDamage(
		amount,
		humanoid.Health,
		state.absorption,
		Rules.armorPoints(state.inv.armor),
		Combat.getProtection(victim),
		opts.ignoreArmor
	)
	state.absorption = absorption
	setAbsorptionAttribute(victim, absorption)
	state.lastDamaged = now
	if opts.attacker and opts.attacker ~= victim then
		state.lastHit = { by = opts.attacker, cause = opts.cause, time = now }
	elseif state.lastHit.by == nil or now - state.lastHit.time > Config.Combat.KillCredit then
		state.lastHit = { by = nil, cause = opts.cause, time = now }
	end
	state.deathCause = opts.cause

	if state.character then
		flash(state.character)
	end
	if state.root and opts.cause ~= "void" then
		hitSound(state.root)
	end
	if opts.knockback then
		Combat.knockback(victim, opts.knockback)
	end
	humanoid.Health = math.max(0, health)
	return true
end

function Combat.kill(victim: Player, cause: string)
	local state = Session.get(victim)
	if not state or not state.alive or not state.humanoid then
		return
	end
	state.deathCause = cause
	state.humanoid.Health = 0
end

function Combat.heal(player: Player, amount: number)
	local state = Session.get(player)
	if state and state.alive and state.humanoid then
		local humanoid = state.humanoid
		humanoid.Health = math.min(humanoid.MaxHealth, humanoid.Health + amount)
	end
end

function Combat.setAbsorption(player: Player, amount: number, duration: number)
	local state = Session.get(player)
	if state then
		state.absorption = math.max(state.absorption, amount)
		state.absorptionUntil = os.clock() + duration
		setAbsorptionAttribute(player, state.absorption)
	end
end

-- 클라이언트가 "이 사람을 때렸다"고 보내면 거리/쿨다운/팀을 확인하고 피해를 줍니다.
function Combat.melee(attacker: Player, victim: Player)
	if attacker == victim then
		return
	end
	local a = Session.get(attacker)
	local v = Session.get(victim)
	if not a or not v or not a.alive or not v.alive or not a.root or not v.root then
		return
	end
	if not Combat.canDamage(attacker, victim) then
		return
	end
	local now = os.clock()
	if now - a.lastAttack < 0.07 then
		return
	end
	a.lastAttack = now
	local offset = v.root.Position - a.root.Position
	if offset.Magnitude > Config.Reach.AttackServer then
		return
	end
	if now < v.hurtUntil then
		return
	end
	v.hurtUntil = now + Config.Combat.HurtTime

	local stack = a.inv.slots[a.inv.selected]
	local damage = Rules.meleeDamage(if stack then stack.id else nil, Combat.getSharpness(attacker))
	local falling = a.root.AssemblyLinearVelocity.Y < -3
		and a.humanoid ~= nil
		and a.humanoid.FloorMaterial == Enum.Material.Air
	if falling then
		damage *= Config.Combat.CritMultiplier
		Net.send(attacker, "crit", victim)
	end
	Net.broadcast("swing", attacker)
	local flat = Vector3.new(offset.X, 0, offset.Z)
	local dir = if flat.Magnitude > 0.05 then flat.Unit else a.root.CFrame.LookVector
	local knock = dir * Config.Combat.KnockbackH + Vector3.new(0, Config.Combat.KnockbackV, 0)
	Combat.damage(victim, damage, { attacker = attacker, cause = "melee", knockback = knock })
end

-- 자연 회복: 마지막으로 맞고 몇 초 지나면 조금씩 찹니다.
task.spawn(function()
	local progress: { [Player]: number } = {}
	while true do
		local dt = task.wait(0.5)
		local now = os.clock()
		for player, state in Session.all() do
			local humanoid = state.humanoid
			if state.alive and humanoid and humanoid.Health > 0 and humanoid.Health < humanoid.MaxHealth then
				if now - state.lastDamaged >= Config.Combat.RegenDelay then
					progress[player] = (progress[player] or 0) + dt
					local interval = Combat.getRegenInterval(player)
					if progress[player] >= interval then
						progress[player] = 0
						humanoid.Health = math.min(humanoid.MaxHealth, humanoid.Health + 1)
					end
				end
			end
			if state.absorption > 0 and now > state.absorptionUntil then
				state.absorption = 0
				setAbsorptionAttribute(player, 0)
			end
		end
		for player in progress do
			if not player.Parent then
				progress[player] = nil
			end
		end
	end
end)

return Combat
