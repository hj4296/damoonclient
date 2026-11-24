-- 날아가는 물체 (화살, 눈덩이, 순간이동 진주, 화염구). 서버에서 직접 움직이며 레이캐스트로 맞았는지 확인합니다.
local Players = game:GetService("Players")
local RunService = game:GetService("RunService")

local Projectiles = {}

export type Spec = {
	shooter: Player,
	origin: Vector3,
	velocity: Vector3,
	gravity: number,
	lifetime: number?,
	size: Vector3,
	color: Color3,
	material: Enum.Material?,
	shape: Enum.PartType?,
	light: boolean?,
	onHitPlayer: (victim: Player, position: Vector3, velocity: Vector3) -> (),
	onHitWorld: (position: Vector3, normal: Vector3, part: BasePart) -> (),
	onExpire: ((position: Vector3) -> ())?,
}

type Active = {
	spec: Spec,
	position: Vector3,
	velocity: Vector3,
	born: number,
	part: BasePart,
}

local folder = Instance.new("Folder")
folder.Name = "Projectiles"
folder.Parent = workspace

local active: { Active } = {}

function Projectiles.launch(spec: Spec)
	local part = Instance.new("Part")
	part.Name = "Projectile"
	part.Anchored = true
	part.CanCollide = false
	part.CanQuery = false
	part.CanTouch = false
	part.CastShadow = false
	part.Size = spec.size
	part.Color = spec.color
	part.Material = spec.material or Enum.Material.SmoothPlastic
	if spec.shape then
		part.Shape = spec.shape
	end
	if spec.light then
		local light = Instance.new("PointLight")
		light.Range = 10
		light.Color = spec.color
		light.Parent = part
	end
	part.CFrame = CFrame.lookAt(spec.origin, spec.origin + spec.velocity)
	part.Parent = folder
	table.insert(active, {
		spec = spec,
		position = spec.origin,
		velocity = spec.velocity,
		born = os.clock(),
		part = part,
	})
end

local function playerFromPart(part: BasePart): Player?
	local model = part:FindFirstAncestorOfClass("Model")
	while model do
		if model:FindFirstChildOfClass("Humanoid") then
			return Players:GetPlayerFromCharacter(model)
		end
		model = model:FindFirstAncestorOfClass("Model")
	end
	return nil
end

local function step(item: Active, dt: number): boolean
	local spec = item.spec
	local gravity = Vector3.new(0, -spec.gravity, 0)
	local displacement = item.velocity * dt + gravity * (0.5 * dt * dt)
	local params = RaycastParams.new()
	params.FilterType = Enum.RaycastFilterType.Exclude
	local exclude: { Instance } = { folder }
	if spec.shooter.Character then
		table.insert(exclude, spec.shooter.Character)
	end
	params.FilterDescendantsInstances = exclude

	local origin = item.position
	local remaining = displacement
	for _ = 1, 4 do
		local result = workspace:Raycast(origin, remaining, params)
		if not result then
			break
		end
		local hitPart = result.Instance
		local victim = playerFromPart(hitPart)
		if victim then
			spec.onHitPlayer(victim, result.Position, item.velocity)
			return false
		end
		if hitPart.CanCollide then
			spec.onHitWorld(result.Position, result.Normal, hitPart)
			return false
		end
		-- 물, 용암 같은 통과하는 파트는 무시하고 이어서 검사
		params:AddToFilter(hitPart)
		remaining = remaining - (result.Position - origin)
		origin = result.Position
		if remaining.Magnitude < 0.01 then
			break
		end
	end

	item.position += displacement
	item.velocity += gravity * dt
	if item.velocity.Magnitude > 0.1 then
		item.part.CFrame = CFrame.lookAt(item.position, item.position + item.velocity)
	end
	local lifetime = spec.lifetime or 6
	if os.clock() - item.born > lifetime or item.position.Y < -400 then
		if spec.onExpire then
			spec.onExpire(item.position)
		end
		return false
	end
	return true
end

RunService.Heartbeat:Connect(function(dt)
	for i = #active, 1, -1 do
		local item = active[i]
		local ok, keep = pcall(step, item, dt)
		if not ok then
			warn("[블록 아레나] 투사체 오류:", keep)
			keep = false
		end
		if not keep then
			item.part:Destroy()
			table.remove(active, i)
		end
	end
end)

function Projectiles.clearFor(shooter: Player)
	for i = #active, 1, -1 do
		if active[i].spec.shooter == shooter then
			active[i].part:Destroy()
			table.remove(active, i)
		end
	end
end

return Projectiles
