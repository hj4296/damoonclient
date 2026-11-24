-- 블록 월드: 격자 좌표(x, y, z)마다 블록 하나를 파트로 만들어 관리합니다.
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Blocks = require(Shared.Blocks)

local B = Config.BlockSize

local World = {}
World.__index = World

export type Record = {
	id: string,
	part: BasePart,
	x: number,
	y: number,
	z: number,
	placed: boolean, -- 플레이어가 설치한 블록인지
	data: { [string]: any }?,
}

export type World = typeof(setmetatable(
	{} :: {
		origin: Vector3,
		model: Model,
		blocksFolder: Folder,
		extras: Folder,
		cells: { [number]: Record },
		count: number,
	},
	World
))

function World.key(x: number, y: number, z: number): number
	return (x + 1024) + (y + 1024) * 2048 + (z + 1024) * 4194304
end

function World.new(origin: Vector3, name: string): World
	local model = Instance.new("Model")
	model.Name = name
	model:SetAttribute("Origin", origin)
	model:SetAttribute("BlockSize", B)
	model:AddTag("BlockWorld")

	local blocksFolder = Instance.new("Folder")
	blocksFolder.Name = "Blocks"
	blocksFolder.Parent = model

	local extras = Instance.new("Folder")
	extras.Name = "Extras"
	extras.Parent = model

	return setmetatable({
		origin = origin,
		model = model,
		blocksFolder = blocksFolder,
		extras = extras,
		cells = {},
		count = 0,
	}, World)
end

function World.cellCenter(self: World, x: number, y: number, z: number): Vector3
	return self.origin + Vector3.new(x * B, y * B, z * B)
end

-- 블록 위에 서는 위치(발 높이) 바로 위 칸의 중심
function World.standCFrame(self: World, x: number, y: number, z: number, lookAt: Vector3?): CFrame
	local pos = self:cellCenter(x, y, z) + Vector3.new(0, 1.6, 0)
	if lookAt then
		local flat = Vector3.new(lookAt.X, pos.Y, lookAt.Z)
		if (flat - pos).Magnitude > 0.1 then
			return CFrame.lookAt(pos, flat)
		end
	end
	return CFrame.new(pos)
end

function World.toCell(self: World, pos: Vector3): (number, number, number)
	local rel = (pos - self.origin) / B
	return math.round(rel.X) + 0, math.round(rel.Y) + 0, math.round(rel.Z) + 0
end

function World.get(self: World, x: number, y: number, z: number): Record?
	return self.cells[World.key(x, y, z)]
end

function World.isSolid(self: World, x: number, y: number, z: number): boolean
	local record = self.cells[World.key(x, y, z)]
	if not record then
		return false
	end
	local def = Blocks.get(record.id)
	return def ~= nil and def.canCollide ~= false
end

local function buildPart(def: Blocks.BlockDef, x: number, y: number, z: number, center: Vector3): BasePart
	local part = Instance.new("Part")
	part.Name = def.id
	part.Anchored = true
	local size = def.size or Vector3.one
	part.Size = size * B
	local offset = Vector3.new(0, (size.Y - 1) * B / 2, 0)
	part.CFrame = CFrame.new(center + offset)
	part.Color = Blocks.shade(def, x, y, z)
	part.Material = def.material
	part.Transparency = def.transparency or 0
	part.CanCollide = def.canCollide ~= false
	part.TopSurface = Enum.SurfaceType.Smooth
	part.BottomSurface = Enum.SurfaceType.Smooth
	if def.light then
		local light = Instance.new("PointLight")
		light.Range = def.light
		light.Brightness = 1.4
		light.Color = def.color
		light.Shadows = false
		light.Parent = part
	end
	if def.textureId then
		for _, face in Enum.NormalId:GetEnumItems() do
			local texture = Instance.new("Texture")
			texture.Texture = def.textureId
			texture.Face = face
			texture.StudsPerTileU = B
			texture.StudsPerTileV = B
			texture.Parent = part
		end
	end
	if def.kill then
		part:AddTag("Kill")
	end
	if def.chest then
		local lid = Instance.new("Part")
		lid.Name = "Latch"
		lid.Anchored = true
		lid.CanCollide = false
		lid.CanQuery = false
		lid.Size = Vector3.new(0.5, 0.7, 0.2)
		lid.Color = Color3.fromRGB(70, 70, 75)
		lid.Material = Enum.Material.Metal
		lid.CFrame = part.CFrame * CFrame.new(0, 0.3, -part.Size.Z / 2 - 0.05)
		lid.Parent = part
		local band = Instance.new("Part")
		band.Name = "Band"
		band.Anchored = true
		band.CanCollide = false
		band.CanQuery = false
		band.Size = Vector3.new(part.Size.X + 0.05, 0.18, part.Size.Z + 0.05)
		band.Color = def.color:Lerp(Color3.new(0, 0, 0), 0.35)
		band.Material = Enum.Material.WoodPlanks
		band.CFrame = part.CFrame * CFrame.new(0, 0.35, 0)
		band.Parent = part
	end
	return part
end

function World.set(self: World, x: number, y: number, z: number, id: string, placed: boolean?, data: { [string]: any }?): Record?
	local def = Blocks.get(id)
	if not def then
		warn("[블록 아레나] 알 수 없는 블록:", id)
		return nil
	end
	local key = World.key(x, y, z)
	local old = self.cells[key]
	if old then
		old.part:Destroy()
		self.count -= 1
	end
	local part = buildPart(def, x, y, z, self:cellCenter(x, y, z))
	part.Parent = self.blocksFolder
	local record: Record = {
		id = id,
		part = part,
		x = x,
		y = y,
		z = z,
		placed = placed == true,
		data = data,
	}
	self.cells[key] = record
	self.count += 1
	return record
end

function World.remove(self: World, x: number, y: number, z: number): Record?
	local key = World.key(x, y, z)
	local record = self.cells[key]
	if record then
		self.cells[key] = nil
		self.count -= 1
		record.part:Destroy()
	end
	return record
end

-- 맵 생성 함수에 넘기는 간단한 그리기 도구
function World.builder(self: World, markers: { [string]: { any } })
	local world = self
	local api = {}
	function api.set(x: number, y: number, z: number, id: string)
		world:set(x, y, z, id)
	end
	function api.get(x: number, y: number, z: number): string?
		local record = world:get(x, y, z)
		return if record then record.id else nil
	end
	function api.clear(x: number, y: number, z: number)
		world:remove(x, y, z)
	end
	function api.fill(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, id: string)
		for x = math.min(x1, x2), math.max(x1, x2) do
			for y = math.min(y1, y2), math.max(y1, y2) do
				for z = math.min(z1, z2), math.max(z1, z2) do
					world:set(x, y, z, id)
				end
			end
		end
	end
	function api.marker(kind: string, data: any)
		markers[kind] = markers[kind] or {}
		table.insert(markers[kind], data)
	end
	-- 격자에 등록하지 않는 큰 장식 파트 (용암 바다 같은 배경용, 부서지지 않음)
	function api.big(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, id: string)
		local def = Blocks.get(id)
		if not def then
			return
		end
		local a = world:cellCenter(math.min(x1, x2), math.min(y1, y2), math.min(z1, z2))
		local b = world:cellCenter(math.max(x1, x2), math.max(y1, y2), math.max(z1, z2))
		local part = buildPart(def, x1, y1, z1, (a + b) / 2)
		part.Size = (b - a) + Vector3.new(B, B, B)
		part.Name = "Decor_" .. id
		part.Parent = world.extras
	end
	return api
end

function World.show(self: World, parent: Instance)
	self.model.Parent = parent
end

function World.destroy(self: World)
	self.model:Destroy()
	table.clear(self.cells)
	self.count = 0
end

return World
