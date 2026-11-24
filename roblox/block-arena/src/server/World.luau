-- 블록 월드: 격자 좌표(x, y, z)마다 블록 하나.
-- 사방이 다른 불투명 블록에 둘러싸여 안 보이는 블록은 파트를 만들지 않고 기록만 해 둡니다.
-- (옆 블록이 부서져 드러나면 그때 파트를 만듭니다.) 그래서 큰 맵도 파트 수가 적습니다.
-- 각 파트에는 "Occ" 속성으로 가려진 면을 적어 두어, 클라이언트가 보이는 면에만 텍스처를 붙입니다.
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage.Shared
local Config = require(Shared.Config)
local Blocks = require(Shared.Blocks)

local B = Config.BlockSize

local World = {}
World.__index = World

-- 면 순서: +X(Right) 1, -X(Left) 2, +Y(Top) 4, -Y(Bottom) 8, +Z(Back) 16, -Z(Front) 32
local DIRS = {
	{ 1, 0, 0, 1 },
	{ -1, 0, 0, 2 },
	{ 0, 1, 0, 4 },
	{ 0, -1, 0, 8 },
	{ 0, 0, 1, 16 },
	{ 0, 0, -1, 32 },
}
World.AllCovered = 63

export type Record = {
	id: string,
	part: BasePart?,
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
		decorFolder: Folder,
		extras: Folder,
		cells: { [number]: Record },
		count: number,
		partCount: number,
		bulk: boolean,
	},
	World
))

function World.key(x: number, y: number, z: number): number
	return (x + 1024) + (y + 1024) * 2048 + (z + 1024) * 4194304
end
local key = World.key

function World.new(origin: Vector3, name: string): World
	local model = Instance.new("Model")
	model.Name = name
	model:SetAttribute("Origin", origin)
	model:SetAttribute("BlockSize", B)
	model:AddTag("BlockWorld")

	local blocksFolder = Instance.new("Folder")
	blocksFolder.Name = "Blocks"
	blocksFolder.Parent = model

	local decorFolder = Instance.new("Folder")
	decorFolder.Name = "Decor"
	decorFolder.Parent = model

	local extras = Instance.new("Folder")
	extras.Name = "Extras"
	extras.Parent = model

	return setmetatable({
		origin = origin,
		model = model,
		blocksFolder = blocksFolder,
		decorFolder = decorFolder,
		extras = extras,
		cells = {},
		count = 0,
		partCount = 0,
		bulk = false,
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
	return self.cells[key(x, y, z)]
end

function World.isSolid(self: World, x: number, y: number, z: number): boolean
	local record = self.cells[key(x, y, z)]
	if not record then
		return false
	end
	local def = Blocks.get(record.id)
	return def ~= nil and def.canCollide ~= false
end

local function occludes(record: Record?): boolean
	if not record then
		return false
	end
	local def = Blocks.get(record.id)
	return def ~= nil and def.occludes
end

-- 여섯 면 중 불투명한 이웃에 가려진 면 (비트 합)
function World.mask(self: World, x: number, y: number, z: number): number
	local cells = self.cells
	local m = 0
	for _, d in DIRS do
		if occludes(cells[key(x + d[1], y + d[2], z + d[3])]) then
			m += d[4]
		end
	end
	return m
end

local function child(parent: BasePart, name: string, size: Vector3, cframe: CFrame, color: Color3, material: Enum.Material?)
	local p = Instance.new("Part")
	p.Name = name
	p.Anchored = true
	p.CanCollide = false
	p.CanQuery = false
	p.CanTouch = false
	p.CastShadow = false
	p.Size = size
	p.CFrame = cframe
	p.Color = color
	p.Material = material or Enum.Material.SmoothPlastic
	p.TopSurface = Enum.SurfaceType.Smooth
	p.BottomSurface = Enum.SurfaceType.Smooth
	p.Parent = parent
	return p
end

local SMALL = { plant = true, grass = true, carpet = true, torch = true, lantern = true }

local function buildPart(def: Blocks.BlockDef, x: number, y: number, z: number, center: Vector3): BasePart
	local shape = if def.shape then Blocks.Shapes[def.shape] else nil
	local part = Instance.new("Part")
	part.Name = def.id
	part.Anchored = true
	part.Size = (if shape then shape.size else Vector3.one) * B
	local h = (x * 7919 + y * 104729 + z * 1299709) % 360
	local rotation = if def.shape == "grass" then CFrame.Angles(0, math.rad(h), 0) else CFrame.identity
	part.CFrame = CFrame.new(center + (if shape then shape.offset else Vector3.zero) * B) * rotation
	part.Color = Blocks.shade(def, x, y, z)
	part.Material = def.material
	part.Transparency = def.transparency or 0
	part.CanCollide = def.canCollide ~= false
	part.TopSurface = Enum.SurfaceType.Smooth
	part.BottomSurface = Enum.SurfaceType.Smooth
	if def.shape and SMALL[def.shape] then
		part.CastShadow = false
	end
	if def.light then
		local light = Instance.new("PointLight")
		light.Range = def.light
		light.Brightness = 1.3
		light.Color = def.lightColor or def.color
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

	local cf = part.CFrame
	local size = part.Size
	if def.shape == "chest" then
		child(part, "Latch", Vector3.new(0.45, 0.6, 0.12), cf * CFrame.new(0, 0.25, -size.Z / 2 - 0.05), Color3.fromRGB(70, 70, 76), Enum.Material.Metal)
	elseif def.shape == "plant" then
		local bloom = def.bloom or Color3.new(1, 1, 1)
		if def.id == "small_mushroom" then
			child(part, "Cap", Vector3.new(0.8, 0.3, 0.8), cf * CFrame.new(0, size.Y / 2 + 0.1, 0), bloom)
		else
			child(part, "Bloom", Vector3.new(0.55, 0.5, 0.55), cf * CFrame.new(0, size.Y / 2 + 0.15, 0) * CFrame.Angles(0, math.rad(h), 0), bloom)
			child(part, "Leaf", Vector3.new(0.6, 0.08, 0.25), cf * CFrame.new(0.2, -0.1, 0) * CFrame.Angles(0, math.rad(h), math.rad(25)), def.color)
		end
	elseif def.shape == "grass" then
		child(part, "Blade", size, cf * CFrame.Angles(0, math.rad(90), 0), part.Color)
	elseif def.shape == "lantern" then
		local dark = Color3.fromRGB(46, 42, 40)
		child(part, "Cap", Vector3.new(size.X + 0.12, 0.16, size.Z + 0.12), cf * CFrame.new(0, size.Y / 2 + 0.06, 0), dark)
		child(part, "Base", Vector3.new(size.X + 0.12, 0.12, size.Z + 0.12), cf * CFrame.new(0, -size.Y / 2 - 0.04, 0), dark)
		child(part, "Handle", Vector3.new(0.1, 0.3, 0.1), cf * CFrame.new(0, size.Y / 2 + 0.28, 0), dark)
	elseif def.shape == "torch" then
		child(part, "Flame", Vector3.new(0.42, 0.42, 0.42), cf * CFrame.new(0, size.Y / 2 + 0.15, 0), Color3.fromRGB(255, 170, 60), Enum.Material.Neon)
	end
	return part
end

function World.spawnPart(self: World, record: Record, mask: number?)
	local def = Blocks.get(record.id)
	if not def or record.part then
		return
	end
	local part = buildPart(def, record.x, record.y, record.z, self:cellCenter(record.x, record.y, record.z))
	if def.shape == nil then
		part:SetAttribute("Occ", mask or self:mask(record.x, record.y, record.z))
	end
	part.Parent = self.blocksFolder
	record.part = part
	self.partCount += 1
end

-- 이웃이 바뀐 블록: 가려진 면 갱신, 숨어 있었는데 드러났으면 파트 만들기
function World.refresh(self: World, record: Record)
	local m = self:mask(record.x, record.y, record.z)
	local part = record.part
	if part then
		if part:GetAttribute("Occ") ~= nil then
			part:SetAttribute("Occ", m)
		end
	elseif m ~= World.AllCovered then
		self:spawnPart(record, m)
	end
end

local function refreshNeighbors(self: World, x: number, y: number, z: number)
	local cells = self.cells
	for _, d in DIRS do
		local n = cells[key(x + d[1], y + d[2], z + d[3])]
		if n then
			self:refresh(n)
		end
	end
end

function World.set(self: World, x: number, y: number, z: number, id: string, placed: boolean?, data: { [string]: any }?): Record?
	local def = Blocks.get(id)
	if not def then
		warn("[블록 아레나] 알 수 없는 블록:", id)
		return nil
	end
	local k = key(x, y, z)
	local old = self.cells[k]
	if old then
		if old.part then
			old.part:Destroy()
			self.partCount -= 1
		end
	else
		self.count += 1
	end
	local record: Record = {
		id = id,
		part = nil,
		x = x,
		y = y,
		z = z,
		placed = placed == true,
		data = data,
	}
	self.cells[k] = record
	if not self.bulk then
		self:spawnPart(record)
		refreshNeighbors(self, x, y, z)
	end
	return record
end

function World.remove(self: World, x: number, y: number, z: number): Record?
	local k = key(x, y, z)
	local record = self.cells[k]
	if record then
		self.cells[k] = nil
		self.count -= 1
		if record.part then
			record.part:Destroy()
			record.part = nil
			self.partCount -= 1
		end
		if not self.bulk then
			refreshNeighbors(self, x, y, z)
		end
	end
	return record
end

-- 맵을 다 그린 뒤 보이는 블록만 파트로 만듭니다.
function World.materialize(self: World)
	self.bulk = false
	for _, record in self.cells do
		if not record.part then
			local m = self:mask(record.x, record.y, record.z)
			if m ~= World.AllCovered then
				self:spawnPart(record, m)
			end
		end
	end
end

-- 격자에 등록하지 않는 장식 파트 (난간, 들보, 지붕 끝 같은 얇은 조각). 위치/크기는 블록 단위
function World.decor(self: World, cx: number, cy: number, cz: number, sx: number, sy: number, sz: number, id: string, opts: { [string]: any }?): BasePart?
	local def = Blocks.get(id)
	if not def then
		warn("[블록 아레나] 알 수 없는 장식 블록:", id)
		return nil
	end
	local o = opts or {}
	local part = Instance.new("Part")
	part.Name = "Decor_" .. id
	part.Anchored = true
	part.Size = Vector3.new(math.max(0.05, sx * B), math.max(0.05, sy * B), math.max(0.05, sz * B))
	part.CFrame = CFrame.new(self:cellCenter(cx, cy, cz)) * (o.rotation or CFrame.identity)
	part.Color = o.color or def.color
	part.Material = def.material
	part.Transparency = o.transparency or def.transparency or 0
	part.CanCollide = if o.collide ~= nil then o.collide else def.canCollide ~= false
	part.TopSurface = Enum.SurfaceType.Smooth
	part.BottomSurface = Enum.SurfaceType.Smooth
	part:SetAttribute("BlockId", id)
	if def.kill then
		part:AddTag("Kill")
	end
	if def.light then
		local light = Instance.new("PointLight")
		light.Range = def.light
		light.Brightness = 1.3
		light.Color = def.lightColor or def.color
		light.Shadows = false
		light.Parent = part
	end
	part.Parent = self.decorFolder
	return part
end

-- 맵 생성 함수에 넘기는 그리기 도구. 그리는 동안은 파트를 만들지 않습니다(show 할 때 한꺼번에).
function World.builder(self: World, markers: { [string]: { any } })
	local world = self
	world.bulk = true
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
	-- 여러 칸을 덮는 큰 파트 한 개 (벽, 기둥, 용암 바다 같은 배경). 부서지지 않고 텍스처는 칸마다 반복됨
	function api.big(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, id: string)
		local lx, ly, lz = math.min(x1, x2), math.min(y1, y2), math.min(z1, z2)
		local hx, hy, hz = math.max(x1, x2), math.max(y1, y2), math.max(z1, z2)
		world:decor((lx + hx) / 2, (ly + hy) / 2, (lz + hz) / 2, hx - lx + 1, hy - ly + 1, hz - lz + 1, id)
	end
	-- 얇은 장식 조각: 가운데 위치와 크기(블록 단위)
	function api.part(cx: number, cy: number, cz: number, sx: number, sy: number, sz: number, id: string, opts: { [string]: any }?)
		world:decor(cx, cy, cz, sx, sy, sz, id, opts)
	end
	return api
end

function World.show(self: World, parent: Instance)
	if self.bulk then
		self:materialize()
	end
	self.model.Parent = parent
end

function World.destroy(self: World)
	self.model:Destroy()
	table.clear(self.cells)
	self.count = 0
	self.partCount = 0
end

return World
