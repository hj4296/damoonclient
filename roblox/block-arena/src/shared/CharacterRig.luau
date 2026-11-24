-- 블록 캐릭터 몸 (R6 방식 관절). 머리 정육면체, 얇은 몸통, 네모난 팔다리.
-- 서버는 이걸 StarterPlayer.StarterCharacter 로 넣어 모든 플레이어가 이 몸으로 태어나게 하고,
-- 클라이언트는 스킨 미리보기 인형으로도 씁니다. 다리 2스터드 + 몸통 2스터드라 일반 R6 와 서 있는 높이가 같습니다.
local Skins = require(script.Parent.Skins)

local CharacterRig = {}

local P = Skins.PixelStuds

local function size(partName: string): Vector3
	local d = Skins.Parts[partName]
	return Vector3.new(d[1] * P, d[2] * P, d[3] * P)
end

CharacterRig.Sizes = {
	Head = size("Head"),
	Torso = size("Torso"),
	Arm = size("Left Arm"),
	Leg = size("Left Leg"),
}

local function part(model: Model, name: string, partSize: Vector3, cframe: CFrame, collide: boolean): Part
	local p = Instance.new("Part")
	p.Name = name
	p.Size = partSize
	p.CFrame = cframe
	p.CanCollide = collide
	p.Material = Enum.Material.SmoothPlastic
	p.TopSurface = Enum.SurfaceType.Smooth
	p.BottomSurface = Enum.SurfaceType.Smooth
	p.Color = Color3.fromRGB(200, 200, 200)
	p.Parent = model
	return p
end

local function motor(name: string, parent: BasePart, part0: BasePart, part1: BasePart, c0: CFrame, c1: CFrame): Motor6D
	local m = Instance.new("Motor6D")
	m.Name = name
	m.Part0 = part0
	m.Part1 = part1
	m.C0 = c0
	m.C1 = c1
	m.Parent = parent
	return m
end

-- origin: HumanoidRootPart 위치 (발바닥에서 3스터드 위)
function CharacterRig.build(origin: CFrame?, withHumanoid: boolean?): Model
	local base = origin or CFrame.new(0, 3, 0)
	local S = CharacterRig.Sizes
	local model = Instance.new("Model")
	model.Name = "StarterCharacter"

	local root = part(model, "HumanoidRootPart", S.Torso, base, false)
	root.Transparency = 1
	local torso = part(model, "Torso", S.Torso, base, true)
	local head = part(model, "Head", S.Head, base * CFrame.new(0, S.Torso.Y / 2 + S.Head.Y / 2, 0), true)
	local armX = S.Torso.X / 2 + S.Arm.X / 2
	local rightArm = part(model, "Right Arm", S.Arm, base * CFrame.new(armX, 0, 0), false)
	local leftArm = part(model, "Left Arm", S.Arm, base * CFrame.new(-armX, 0, 0), false)
	local legX = S.Leg.X / 2
	local legY = -S.Torso.Y / 2 - S.Leg.Y / 2
	local rightLeg = part(model, "Right Leg", S.Leg, base * CFrame.new(legX, legY, 0), false)
	local leftLeg = part(model, "Left Leg", S.Leg, base * CFrame.new(-legX, legY, 0), false)

	motor("RootJoint", root, root, torso, CFrame.identity, CFrame.identity)
	motor("Neck", torso, torso, head, CFrame.new(0, S.Torso.Y / 2, 0), CFrame.new(0, -S.Head.Y / 2, 0))
	local shoulderY = S.Torso.Y / 2 - S.Arm.X / 2
	local armPivot = S.Arm.Y / 2 - S.Arm.X / 2
	motor("Right Shoulder", torso, torso, rightArm, CFrame.new(armX, shoulderY, 0), CFrame.new(0, armPivot, 0))
	motor("Left Shoulder", torso, torso, leftArm, CFrame.new(-armX, shoulderY, 0), CFrame.new(0, armPivot, 0))
	motor("Right Hip", torso, torso, rightLeg, CFrame.new(legX, -S.Torso.Y / 2, 0), CFrame.new(0, S.Leg.Y / 2, 0))
	motor("Left Hip", torso, torso, leftLeg, CFrame.new(-legX, -S.Torso.Y / 2, 0), CFrame.new(0, S.Leg.Y / 2, 0))

	if withHumanoid ~= false then
		local humanoid = Instance.new("Humanoid")
		humanoid.RigType = Enum.HumanoidRigType.R6
		humanoid.HipHeight = 0
		humanoid.DisplayDistanceType = Enum.HumanoidDisplayDistanceType.None
		humanoid.Parent = model
	end
	model.PrimaryPart = root
	model:SetAttribute("BlockRig", true)
	return model
end

CharacterRig.BodyParts = { "Head", "Torso", "Left Arm", "Right Arm", "Left Leg", "Right Leg" }

return CharacterRig
