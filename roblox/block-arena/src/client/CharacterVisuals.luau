-- 캐릭터 꾸미기 (클라이언트): 픽셀 스킨 입히기, 입은 갑옷 보여 주기, 마인크래프트식 팔다리 흔들기
local AssetService = game:GetService("AssetService")
local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Skins = require(Shared:WaitForChild("Skins"))
local CharacterRig = require(Shared:WaitForChild("CharacterRig"))
local Net = require(script.Parent.Net)

local localPlayer = Players.LocalPlayer

local CharacterVisuals = {}

local SCALE = 4
local FACES = { "Front", "Back", "Left", "Right", "Top", "Bottom" }
local NORMALS = {
	Front = Enum.NormalId.Front,
	Back = Enum.NormalId.Back,
	Left = Enum.NormalId.Left,
	Right = Enum.NormalId.Right,
	Top = Enum.NormalId.Top,
	Bottom = Enum.NormalId.Bottom,
}
local ARMOR_COLORS = {
	leather = Color3.fromRGB(150, 96, 56),
	chain = Color3.fromRGB(150, 154, 166),
	iron = Color3.fromRGB(214, 214, 220),
	diamond = Color3.fromRGB(90, 220, 214),
}

local imageCache: { [string]: any } = {}
local imagesBroken = false
local nametagsVisible = true

type Rig = {
	model: Model,
	root: BasePart,
	motors: { [string]: Motor6D },
	phase: number,
	seed: number,
	swingStart: number?,
}
local rigs: { [Model]: Rig } = {}

local function pixelKey(px: { number }, w: number, h: number): string
	local hash = 7
	for i = 1, #px do
		hash = (hash * 31 + px[i]) % 2147483647
	end
	return w .. "x" .. h .. ":" .. hash
end

-- 픽셀 배열 -> 텍스처 내용 (같은 그림은 한 번만 만듦)
local function makeImage(px: { number }, w: number, h: number): any
	if imagesBroken then
		return nil
	end
	local key = pixelKey(px, w, h)
	local cached = imageCache[key]
	if cached ~= nil then
		return cached
	end
	local ok, result = pcall(function()
		local iw, ih = w * SCALE, h * SCALE
		local image = AssetService:CreateEditableImage({ Size = Vector2.new(iw, ih) })
		if not image then
			return nil
		end
		local buf = buffer.create(iw * ih * 4)
		for y = 0, ih - 1 do
			local row = (y // SCALE) * w
			for x = 0, iw - 1 do
				local src = (row + x // SCALE) * 4
				local dst = (y * iw + x) * 4
				buffer.writeu8(buf, dst, px[src + 1])
				buffer.writeu8(buf, dst + 1, px[src + 2])
				buffer.writeu8(buf, dst + 2, px[src + 3])
				buffer.writeu8(buf, dst + 3, px[src + 4])
			end
		end
		image:WritePixelsBuffer(Vector2.zero, Vector2.new(iw, ih), buf)
		return Content.fromObject(image)
	end)
	if not ok or result == nil then
		imagesBroken = true
		return nil
	end
	imageCache[key] = result
	return result
end

local function faceStuds(size: Vector3, face: string): (number, number)
	if face == "Front" or face == "Back" then
		return size.X, size.Y
	elseif face == "Left" or face == "Right" then
		return size.Z, size.Y
	end
	return size.X, size.Z
end

local function smallPart(parent: Instance, name: string, size: Vector3, color: Color3, attach: BasePart, offset: CFrame)
	local p = Instance.new("Part")
	p.Name = name
	p.Size = size
	p.Color = color
	p.Material = Enum.Material.SmoothPlastic
	p.CanCollide = false
	p.CanQuery = false
	p.CanTouch = false
	p.Massless = true
	p.CastShadow = false
	p.TopSurface = Enum.SurfaceType.Smooth
	p.BottomSurface = Enum.SurfaceType.Smooth
	p.CFrame = attach.CFrame * offset
	local weld = Instance.new("Weld")
	weld.Part0 = attach
	weld.Part1 = p
	weld.C0 = offset
	weld.Parent = p
	p.Parent = parent
	return p
end

-- 스킨 입히기: 픽셀 텍스처가 되면 면마다 붙이고, 안 되면 색 + 눈/머리카락 조각으로 대신함
function CharacterVisuals.applySkin(model: Model, code: string)
	local skin = Skins.decode(code)
	local textured = true
	for _, name in CharacterRig.BodyParts do
		local part = model:FindFirstChild(name)
		if part and part:IsA("BasePart") then
			part.Color = Skins.partColor(skin, name)
			for _, child in part:GetChildren() do
				if child.Name == "SK" then
					child:Destroy()
				end
			end
			for _, face in FACES do
				local px, w, h = Skins.face(skin, name, face)
				local content = makeImage(px, w, h)
				if not content then
					textured = false
					break
				end
				local t = Instance.new("Texture")
				t.Name = "SK"
				t.Face = NORMALS[face]
				local u, v = faceStuds(part.Size, face)
				t.StudsPerTileU = u
				t.StudsPerTileV = v
				t.TextureContent = content
				t.Parent = part
			end
		end
	end
	if not textured then
		-- 도중에 실패했으면 붙인 텍스처를 모두 떼고 색 방식으로
		for _, name in CharacterRig.BodyParts do
			local part = model:FindFirstChild(name)
			if part then
				for _, child in part:GetChildren() do
					if child.Name == "SK" then
						child:Destroy()
					end
				end
			end
		end
	end
	local old = model:FindFirstChild("SkinDetail")
	if old then
		old:Destroy()
	end
	local head = model:FindFirstChild("Head")
	if not textured and head and head:IsA("BasePart") then
		-- 텍스처를 못 쓰면 작은 조각으로 눈과 머리카락을 표시
		local folder = Instance.new("Folder")
		folder.Name = "SkinDetail"
		local s = head.Size
		local eye = Skins.color(skin.eyes)
		local white = Color3.fromRGB(245, 245, 245)
		local px = s.X / 8
		for _, e in { { -2.5, white }, { -1.5, eye }, { 1.5, eye }, { 2.5, white } } do
			smallPart(folder, "Eye", Vector3.new(px, px, 0.05), e[2], head, CFrame.new(e[1] * px, -0.5 * px, -s.Z / 2 - 0.02))
		end
		if skin.hairStyle ~= "bald" then
			local hairColor = if skin.hairStyle == "cap" then Skins.color(skin.shirt) else Skins.color(skin.hair)
			smallPart(folder, "Hair", Vector3.new(s.X + 0.06, px * 2 + 0.03, s.Z + 0.06), hairColor, head, CFrame.new(0, s.Y / 2 - px, 0))
			if skin.hairStyle ~= "cap" then
				local length = if skin.hairStyle == "long" then 8 else 5
				smallPart(folder, "HairBack", Vector3.new(s.X + 0.06, px * length, 0.08), hairColor, head, CFrame.new(0, s.Y / 2 - px * length / 2, s.Z / 2 + 0.02))
			end
		end
		folder.Parent = model
	end
end

-- 갑옷 겉모습 (캐릭터 속성 "Armor": "helmet=leather:ff5555;chestplate=iron")
local function overlay(folder: Instance, part: BasePart, fromTop: number, toTop: number, color: Color3, tier: string)
	local s = part.Size
	local height = s.Y * (toTop - fromTop)
	local offsetY = s.Y / 2 - (fromTop + toTop) / 2 * s.Y
	local piece = smallPart(folder, "ArmorPiece", Vector3.new(s.X + 0.14, height + 0.08, s.Z + 0.14), color, part, CFrame.new(0, offsetY, 0))
	if tier == "iron" or tier == "chain" then
		piece.Material = Enum.Material.Metal
	end
	if tier == "chain" then
		piece.Transparency = 0.25
	end
end

function CharacterVisuals.applyArmor(model: Model, key: string)
	local old = model:FindFirstChild("ArmorVisual")
	if old then
		old:Destroy()
	end
	if key == "" then
		return
	end
	local folder = Instance.new("Folder")
	folder.Name = "ArmorVisual"
	local function bodyPart(name: string): BasePart?
		local p = model:FindFirstChild(name)
		return if p and p:IsA("BasePart") then p else nil
	end
	for entry in string.gmatch(key, "[^;]+") do
		local slot, tier, dye = string.match(entry, "^(%a+)=(%a+):?(%x*)$")
		if slot and tier then
			local color = if dye and #dye == 6 then Color3.fromHex(dye) else (ARMOR_COLORS[tier] or ARMOR_COLORS.iron)
			local function cover(name: string, a: number, b: number)
				local p = bodyPart(name)
				if p then
					overlay(folder, p, a, b, color, tier)
				end
			end
			if slot == "helmet" then
				cover("Head", 0, 0.55)
			elseif slot == "chestplate" then
				cover("Torso", 0, 0.8)
				cover("Left Arm", 0, 0.4)
				cover("Right Arm", 0, 0.4)
			elseif slot == "leggings" then
				cover("Torso", 0.8, 1)
				cover("Left Leg", 0, 0.7)
				cover("Right Leg", 0, 0.7)
			elseif slot == "boots" then
				cover("Left Leg", 0.7, 1)
				cover("Right Leg", 0.7, 1)
			end
		end
	end
	folder.Parent = model
end

local function setNametag(model: Model)
	local head = model:FindFirstChild("Head")
	local tag = head and head:FindFirstChild("Nametag")
	if tag and tag:IsA("BillboardGui") then
		tag.Enabled = nametagsVisible
	end
end

function CharacterVisuals.setNametagsVisible(visible: boolean)
	nametagsVisible = visible
	for model in rigs do
		if model ~= localPlayer.Character then
			setNametag(model)
		end
	end
end

-- 공격/설치할 때 오른팔 휘두르기
function CharacterVisuals.swing(player: Player)
	local model = player.Character
	local rig = if model then rigs[model] else nil
	if rig then
		rig.swingStart = os.clock()
	end
end

local function setup(player: Player, model: Model)
	local root = model:WaitForChild("HumanoidRootPart", 10)
	local torso = model:WaitForChild("Torso", 10)
	if not root or not torso or not root:IsA("BasePart") then
		return
	end
	local motors = {}
	for _, name in { "Right Shoulder", "Left Shoulder", "Right Hip", "Left Hip", "Neck" } do
		local m = torso:WaitForChild(name, 5)
		if m and m:IsA("Motor6D") then
			motors[name] = m
		end
	end
	rigs[model] = { model = model, root = root, motors = motors, phase = 0, seed = player.UserId % 100 }
	model.Destroying:Connect(function()
		rigs[model] = nil
	end)

	for _, name in CharacterRig.BodyParts do
		model:WaitForChild(name, 5)
	end
	local function skin()
		local code = player:GetAttribute("Skin")
		CharacterVisuals.applySkin(model, if type(code) == "string" then code else Skins.DefaultCode)
	end
	local function armor()
		local key = model:GetAttribute("Armor")
		CharacterVisuals.applyArmor(model, if type(key) == "string" then key else "")
	end
	skin()
	armor()
	local skinConn = player:GetAttributeChangedSignal("Skin"):Connect(skin)
	model.AncestryChanged:Connect(function()
		if not model.Parent then
			skinConn:Disconnect()
		end
	end)
	model:GetAttributeChangedSignal("Armor"):Connect(armor)
	-- 이름표는 서버가 조금 늦게 붙일 수 있어서 몇 번 확인
	if player ~= localPlayer then
		task.delay(1, function()
			setNametag(model)
		end)
		task.delay(3, function()
			setNametag(model)
		end)
	end
end

local function watchPlayer(player: Player)
	if player.Character then
		task.spawn(setup, player, player.Character)
	end
	player.CharacterAdded:Connect(function(model)
		setup(player, model)
	end)
end

function CharacterVisuals.init()
	for _, player in Players:GetPlayers() do
		watchPlayer(player)
	end
	Players.PlayerAdded:Connect(watchPlayer)

	Net.on("swing", function(player)
		if typeof(player) == "Instance" and player:IsA("Player") and player ~= localPlayer then
			CharacterVisuals.swing(player)
		end
	end)

	-- 마인크래프트처럼 걸을 때 팔다리를 앞뒤로 흔듦
	RunService.Stepped:Connect(function(_, dt)
		local now = os.clock()
		for model, rig in rigs do
			if not model.Parent or not rig.root.Parent then
				continue
			end
			local v = rig.root.AssemblyLinearVelocity
			local speed = Vector3.new(v.X, 0, v.Z).Magnitude
			local amount = math.clamp(speed / 16, 0, 1)
			rig.phase += dt * (5 + speed * 0.35)
			local swing = math.sin(rig.phase) * 0.85 * amount
			local idle = math.sin(now * 1.7 + rig.seed) * 0.05
			local attack = 0
			if rig.swingStart then
				local t = (now - rig.swingStart) / 0.3
				if t < 1 then
					attack = math.sin(t * math.pi) * 1.4
				else
					rig.swingStart = nil
				end
			end
			local flying = model:GetAttribute("Flying") == true
			local m = rig.motors
			if flying then
				swing = math.sin(now * 3 + rig.seed) * 0.12
			end
			if m["Right Shoulder"] then
				m["Right Shoulder"].Transform = CFrame.Angles(swing + attack, 0, idle * 0.5)
			end
			if m["Left Shoulder"] then
				m["Left Shoulder"].Transform = CFrame.Angles(-swing, 0, -idle * 0.5)
			end
			if m["Right Hip"] then
				m["Right Hip"].Transform = CFrame.Angles(if flying then 0.15 else -swing, 0, 0)
			end
			if m["Left Hip"] then
				m["Left Hip"].Transform = CFrame.Angles(if flying then 0.15 else swing, 0, 0)
			end
		end
	end)
end

return CharacterVisuals
