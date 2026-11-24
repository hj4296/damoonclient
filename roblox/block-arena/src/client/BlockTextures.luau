-- 블록에 픽셀 텍스처 붙이기 (클라이언트)
--  1) TextureIds 에 올린 이미지 ID 가 있으면 그걸 쓰고
--  2) 없으면 Textures 모듈이 그린 픽셀을 EditableImage 로 만들어 씁니다 (업로드 필요 없음)
--  3) 둘 다 안 되면 지금처럼 색 블록으로 보입니다.
-- 카메라 가까운 블록(약 130스터드)에만, 그리고 보이는 면에만 붙여서 가볍게 유지합니다.
local AssetService = game:GetService("AssetService")
local CollectionService = game:GetService("CollectionService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Blocks = require(Shared:WaitForChild("Blocks"))
local Textures = require(Shared:WaitForChild("Textures"))
local TextureIds = require(Shared:WaitForChild("TextureIds"))

local B = Config.BlockSize
local SCALE = 4 -- 16픽셀을 64픽셀로 키워서 덜 흐리게
local CHUNK = 8 * B
local RADIUS = 130
local BUDGET = 260 -- 한 프레임에 처리할 블록 수

local BlockTextures = {}
BlockTextures.mode = "none"

type Source = { content: any?, id: string? }
local sources: { [string]: Source } = {}

type Chunk = { parts: { [BasePart]: boolean }, active: boolean, center: Vector3 }
local chunks: { [string]: Chunk } = {}
local partChunk: { [BasePart]: Chunk } = {}
local textured: { [BasePart]: boolean } = {}
local queue: { { BasePart | boolean } } = {}

local FACES = {
	{ Enum.NormalId.Right, 1, "side" },
	{ Enum.NormalId.Left, 2, "side" },
	{ Enum.NormalId.Top, 4, "top" },
	{ Enum.NormalId.Bottom, 8, "bottom" },
	{ Enum.NormalId.Back, 16, "side" },
	{ Enum.NormalId.Front, 32, "front" },
}

local function makeImage(name: string): any
	local pixels = Textures.get(name)
	if not pixels then
		return nil
	end
	local size = 16 * SCALE
	local image = AssetService:CreateEditableImage({ Size = Vector2.new(size, size) })
	if not image then
		return nil
	end
	local buf = buffer.create(size * size * 4)
	for y = 0, size - 1 do
		local row = (y // SCALE) * 16
		for x = 0, size - 1 do
			local src = (row + x // SCALE) * 4
			local dst = (y * size + x) * 4
			buffer.writeu8(buf, dst, pixels[src + 1])
			buffer.writeu8(buf, dst + 1, pixels[src + 2])
			buffer.writeu8(buf, dst + 2, pixels[src + 3])
			buffer.writeu8(buf, dst + 3, pixels[src + 4])
		end
	end
	image:WritePixelsBuffer(Vector2.zero, Vector2.new(size, size), buf)
	return Content.fromObject(image)
end

local function loadSources()
	for name, id in TextureIds :: { [string]: string } do
		sources[name] = { id = id }
	end
	local made = 0
	local ok, err = pcall(function()
		for _, name in Textures.names() do
			if not sources[name] then
				local content = makeImage(name)
				if content then
					sources[name] = { content = content }
					made += 1
				end
			end
		end
	end)
	if not ok then
		warn(
			"[블록 아레나] 텍스처를 만들 수 없어 색 블록으로 보입니다. 게임 설정 → 보안 → '메시/이미지 API 허용'을 켜거나 "
				.. "textures 폴더의 PNG 를 올려 TextureIds 에 적어 주세요. 이유: "
				.. tostring(err)
		)
	end
	if made > 0 then
		BlockTextures.mode = "generated"
	elseif next(sources) then
		BlockTextures.mode = "uploaded"
	end
end

local function blockDef(part: BasePart): Blocks.BlockDef?
	local id = part:GetAttribute("BlockId")
	return Blocks.get(if type(id) == "string" then id else part.Name)
end

local function untexture(part: BasePart)
	for _, child in part:GetChildren() do
		if child.Name == "BT" then
			child:Destroy()
		end
	end
	textured[part] = nil
end

local function texture(part: BasePart)
	if not part.Parent then
		return
	end
	untexture(part)
	local def = blockDef(part)
	if not def or def.tex == false then
		return
	end
	local mask = 0
	if def.shape == nil then
		local occ = part:GetAttribute("Occ")
		mask = if type(occ) == "number" then occ else 0
	end
	for _, face in FACES do
		local onlyTop = def.shape == "carpet"
		if bit32.band(mask, face[2]) == 0 and (not onlyTop or face[3] == "top") then
			local name = Blocks.faceTexture(def, face[3])
			local source = if name then sources[name] else nil
			if source then
				local t = Instance.new("Texture")
				t.Name = "BT"
				t.Face = face[1]
				t.StudsPerTileU = B
				t.StudsPerTileV = B
				if source.content then
					t.TextureContent = source.content
				elseif source.id then
					t.Texture = source.id
				end
				t.Parent = part
			end
		end
	end
	textured[part] = true
end

local function chunkFor(position: Vector3): Chunk
	local cx, cy, cz = position.X // CHUNK, position.Y // CHUNK, position.Z // CHUNK
	local k = cx .. "," .. cy .. "," .. cz
	local chunk = chunks[k]
	if not chunk then
		chunk = {
			parts = {},
			active = false,
			center = Vector3.new((cx + 0.5) * CHUNK, (cy + 0.5) * CHUNK, (cz + 0.5) * CHUNK),
		}
		chunks[k] = chunk
	end
	return chunk
end

local function track(part: Instance)
	if not part:IsA("BasePart") or partChunk[part] then
		return
	end
	-- 장식 파트(벽, 기둥 같은 큰 조각)는 수가 적어서 거리와 상관없이 항상 텍스처를 붙임
	if part.Parent and part.Parent.Name == "Decor" then
		texture(part)
		return
	end
	local chunk = chunkFor(part.Position)
	chunk.parts[part] = true
	partChunk[part] = chunk
	if chunk.active then
		table.insert(queue, { part, true })
	end
	part:GetAttributeChangedSignal("Occ"):Connect(function()
		if textured[part] then
			table.insert(queue, { part, true })
		end
	end)
end

local function untrack(part: Instance)
	if not part:IsA("BasePart") then
		return
	end
	local chunk = partChunk[part]
	if chunk then
		chunk.parts[part] = nil
	end
	partChunk[part] = nil
	textured[part] = nil
end

local function watchFolder(folder: Instance)
	for _, child in folder:GetChildren() do
		track(child)
	end
	folder.ChildAdded:Connect(track)
	folder.ChildRemoved:Connect(untrack)
end

local function watchWorld(model: Instance)
	for _, name in { "Blocks", "Decor" } do
		local folder = model:FindFirstChild(name)
		if folder then
			watchFolder(folder)
		else
			task.spawn(function()
				local f = model:WaitForChild(name, 30)
				if f then
					watchFolder(f)
				end
			end)
		end
	end
end

local loaded = false

-- 텍스처 이미지 준비 (핫바 아이콘도 이걸 씀). 여러 번 불러도 한 번만 실행
function BlockTextures.load()
	if not loaded then
		loaded = true
		loadSources()
	end
end

function BlockTextures.source(name: string): Source?
	return sources[name]
end

function BlockTextures.init()
	BlockTextures.load()
	if BlockTextures.mode == "none" then
		return
	end
	for _, model in CollectionService:GetTagged("BlockWorld") do
		watchWorld(model)
	end
	CollectionService:GetInstanceAddedSignal("BlockWorld"):Connect(watchWorld)

	local timer = 0
	RunService.Heartbeat:Connect(function(dt)
		timer += dt
		if timer >= 0.3 then
			timer = 0
			local camera = workspace.CurrentCamera
			local focus = if camera then camera.CFrame.Position else Vector3.zero
			for _, chunk in chunks do
				local distance = (chunk.center - focus).Magnitude
				if not chunk.active and distance < RADIUS then
					chunk.active = true
					for part in chunk.parts do
						table.insert(queue, { part, true })
					end
				elseif chunk.active and distance > RADIUS + 48 then
					chunk.active = false
					for part in chunk.parts do
						table.insert(queue, { part, false })
					end
				end
			end
		end
		local done = 0
		while done < BUDGET and #queue > 0 do
			local item = table.remove(queue) :: { any }
			local part = item[1] :: BasePart
			local chunk = partChunk[part]
			if part.Parent and chunk then
				if item[2] and chunk.active then
					texture(part)
				elseif not item[2] and not chunk.active then
					untexture(part)
				end
			end
			done += 1
		end
	end)
end

return BlockTextures
