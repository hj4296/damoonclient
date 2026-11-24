-- 캐릭터 스킨: 색 6가지 + 머리 모양 + 옷 모양. 문자열 하나로 저장/전송합니다.
-- 스킨 픽셀(머리 8x8x8, 몸 8x12x4, 팔/다리 4x12x4)을 면마다 그려 줍니다. 기본 스킨은 이 게임의 오리지널 디자인입니다.
local Skins = {}

export type Skin = {
	skin: string,
	hair: string,
	eyes: string,
	shirt: string,
	pants: string,
	shoes: string,
	hairStyle: string,
	shirtStyle: string,
}

Skins.Palettes = {
	skin = { "FFE0C8", "F5D0B0", "EBBE96", "D8A57C", "B9825A", "8D5A3C", "63402A", "A8C8A0" },
	hair = { "1E1E1E", "2B1E16", "4A2F1D", "7A4A26", "C68A3A", "E8C870", "D9D9D9", "B03A2E", "3E64C8", "8E44AD" },
	eyes = { "3A5BA0", "3D7A3A", "5A3A22", "222222", "7B4AA8", "1E9AA0", "B03A2E" },
	shirt = { "C83C32", "3C6EC8", "3CA050", "E0B020", "8E44AD", "E67E22", "ECECEC", "2B2B2B", "1ABC9C", "F06292", "8A8F98", "6B4226" },
	pants = { "3C3C46", "2E3F6E", "5A4632", "1E1E1E", "6E6E6E", "2E5E3E", "8B5A2B", "ECECEC", "7A1F1F" },
	shoes = { "2A2A2A", "5A3A22", "ECECEC", "8B1E1E", "2E3F6E", "C68A3A" },
}
Skins.ColorOrder = { "skin", "hair", "eyes", "shirt", "pants", "shoes" }
Skins.ColorNames = { skin = "피부", hair = "머리카락", eyes = "눈", shirt = "옷", pants = "바지", shoes = "신발" }

Skins.HairStyles = { "short", "long", "cap", "bald" }
Skins.HairStyleNames = { short = "짧은 머리", long = "긴 머리", cap = "모자", bald = "민머리" }
Skins.ShirtStyles = { "hoodie", "plain", "stripe", "vest" }
Skins.ShirtStyleNames = { hoodie = "후드", plain = "기본", stripe = "줄무늬", vest = "조끼" }

Skins.Default = {
	skin = "EBBE96",
	hair = "2B1E16",
	eyes = "3A5BA0",
	shirt = "C83C32",
	pants = "3C3C46",
	shoes = "2A2A2A",
	hairStyle = "short",
	shirtStyle = "hoodie",
} :: Skin

Skins.Presets = {
	{ name = "기본", skin = Skins.Default },
	{
		name = "기사",
		skin = { skin = "F5D0B0", hair = "C68A3A", eyes = "3D7A3A", shirt = "8A8F98", pants = "2E3F6E", shoes = "5A3A22", hairStyle = "short", shirtStyle = "vest" },
	},
	{
		name = "해적",
		skin = { skin = "D8A57C", hair = "1E1E1E", eyes = "5A3A22", shirt = "C83C32", pants = "5A4632", shoes = "2A2A2A", hairStyle = "cap", shirtStyle = "stripe" },
	},
	{
		name = "요리사",
		skin = { skin = "F5D0B0", hair = "4A2F1D", eyes = "3A5BA0", shirt = "ECECEC", pants = "1E1E1E", shoes = "2A2A2A", hairStyle = "cap", shirtStyle = "plain" },
	},
	{
		name = "숲 탐험가",
		skin = { skin = "B9825A", hair = "7A4A26", eyes = "3D7A3A", shirt = "3CA050", pants = "5A4632", shoes = "5A3A22", hairStyle = "long", shirtStyle = "vest" },
	},
}

local function contains(list: { string }, value: any): boolean
	return type(value) == "string" and table.find(list, value) ~= nil
end

-- 잘못된 값은 기본값으로 바꿔서 항상 올바른 스킨을 돌려줍니다.
function Skins.sanitize(input: { [string]: any }?): Skin
	local src = input or {}
	local out = {}
	for _, key in Skins.ColorOrder do
		local value = src[key]
		out[key] = if contains(Skins.Palettes[key], value) then value else (Skins.Default :: any)[key]
	end
	out.hairStyle = if contains(Skins.HairStyles, src.hairStyle) then src.hairStyle else Skins.Default.hairStyle
	out.shirtStyle = if contains(Skins.ShirtStyles, src.shirtStyle) then src.shirtStyle else Skins.Default.shirtStyle
	return out :: any
end

function Skins.encode(skin: Skin): string
	local s = Skins.sanitize(skin :: any)
	return table.concat({ "v1", s.skin, s.hair, s.eyes, s.shirt, s.pants, s.shoes, s.hairStyle, s.shirtStyle }, "|")
end

function Skins.decode(text: any): Skin
	if type(text) ~= "string" then
		return Skins.sanitize(nil)
	end
	local p = string.split(text, "|")
	if p[1] ~= "v1" then
		return Skins.sanitize(nil)
	end
	return Skins.sanitize({ skin = p[2], hair = p[3], eyes = p[4], shirt = p[5], pants = p[6], shoes = p[7], hairStyle = p[8], shirtStyle = p[9] })
end

Skins.DefaultCode = Skins.encode(Skins.Default)

function Skins.isDefault(text: string): boolean
	return Skins.encode(Skins.decode(text)) == Skins.DefaultCode
end

function Skins.color(hex: string): Color3
	return Color3.fromHex(hex)
end

----------------------------------------------------------------------------
-- 픽셀 그리기
----------------------------------------------------------------------------

-- 몸 부위 크기 (픽셀). 1픽셀 = 1/6 스터드
Skins.PixelStuds = 1 / 6
Skins.Parts = {
	Head = { 8, 8, 8 },
	Torso = { 8, 12, 4 },
	["Left Arm"] = { 4, 12, 4 },
	["Right Arm"] = { 4, 12, 4 },
	["Left Leg"] = { 4, 12, 4 },
	["Right Leg"] = { 4, 12, 4 },
}

type Pixels = { number }
type RGB = { number }

local function rgb(hex: string): RGB
	local c = Color3.fromHex(hex)
	return { c.R * 255, c.G * 255, c.B * 255 }
end

local function mul(c: RGB, f: number): RGB
	return { c[1] * f, c[2] * f, c[3] * f }
end

local function canvas(w: number, h: number, c: RGB, seed: number): Pixels
	local px = table.create(w * h * 4, 255)
	for y = 0, h - 1 do
		for x = 0, w - 1 do
			local n = ((x * 7349 + y * 1597 + seed * 31) % 97) / 97 -- 0~1 고정된 얼룩
			local f = 0.95 + n * 0.08
			local i = (y * w + x) * 4
			px[i + 1] = math.clamp(math.floor(c[1] * f + 0.5), 0, 255)
			px[i + 2] = math.clamp(math.floor(c[2] * f + 0.5), 0, 255)
			px[i + 3] = math.clamp(math.floor(c[3] * f + 0.5), 0, 255)
		end
	end
	return px
end

local function put(px: Pixels, w: number, x: number, y: number, c: RGB)
	local i = (y * w + x) * 4
	px[i + 1] = math.clamp(math.floor(c[1] + 0.5), 0, 255)
	px[i + 2] = math.clamp(math.floor(c[2] + 0.5), 0, 255)
	px[i + 3] = math.clamp(math.floor(c[3] + 0.5), 0, 255)
	px[i + 4] = 255
end

local function rows(px: Pixels, w: number, y1: number, y2: number, c: RGB)
	for y = y1, y2 do
		for x = 0, w - 1 do
			put(px, w, x, y, mul(c, 0.96 + ((x * 3 + y * 5) % 4) * 0.025))
		end
	end
end

-- 면 크기: Front/Back = 가로 x 세로, Left/Right = 깊이 x 세로, Top/Bottom = 가로 x 깊이
function Skins.faceSize(partName: string, face: string): (number, number)
	local dims = Skins.Parts[partName]
	local w, h, d = dims[1], dims[2], dims[3]
	if face == "Front" or face == "Back" then
		return w, h
	elseif face == "Left" or face == "Right" then
		return d, h
	end
	return w, d
end

local function head(s: Skin, face: string): Pixels
	local w, h = Skins.faceSize("Head", face)
	local skin, hair = rgb(s.skin), rgb(s.hair)
	local hat = mul(rgb(s.shirt), 0.9)
	local style = s.hairStyle
	local px = canvas(w, h, skin, 1)
	if face == "Top" then
		return canvas(w, h, if style == "cap" then hat elseif style == "bald" then skin else hair, 2)
	elseif face == "Bottom" then
		return px
	end
	if style == "cap" then
		rows(px, w, 0, 2, hat)
		if face == "Front" then
			rows(px, w, 3, 3, mul(hat, 0.75))
		end
	end
	if face == "Front" then
		if style == "short" then
			rows(px, w, 0, 1, hair)
			put(px, w, 0, 2, hair)
			put(px, w, 7, 2, hair)
		elseif style == "long" then
			rows(px, w, 0, 1, hair)
			for y = 2, 7 do
				put(px, w, 0, y, hair)
				put(px, w, 7, y, hair)
			end
		end
		local eye = rgb(s.eyes)
		local white = { 245, 245, 245 }
		put(px, w, 1, 4, white)
		put(px, w, 2, 4, eye)
		put(px, w, 5, 4, eye)
		put(px, w, 6, 4, white)
		put(px, w, 3, 5, mul(skin, 0.9))
		put(px, w, 4, 5, mul(skin, 0.9))
		put(px, w, 3, 6, mul(skin, 0.72))
		put(px, w, 4, 6, mul(skin, 0.72))
	elseif face == "Back" then
		local last = if style == "long" then 7 elseif style == "short" then 5 elseif style == "cap" then 4 else -1
		if last >= 0 then
			rows(px, w, if style == "cap" then 3 else 0, last, hair)
		end
	else
		-- 옆면: 뒤쪽 절반에 머리카락, 앞쪽 아래는 귀
		local backOnLeft = face == "Right"
		for y = 0, h - 1 do
			for x = 0, w - 1 do
				local fromBack = if backOnLeft then x else w - 1 - x
				local isHair = false
				if style == "short" then
					isHair = y <= 1 or (y <= 4 and fromBack <= 3)
				elseif style == "long" then
					isHair = y <= 1 or fromBack <= 4
				elseif style == "cap" then
					isHair = y == 3 and fromBack <= 4
				end
				if isHair then
					put(px, w, x, y, mul(hair, 0.96 + ((x + y) % 3) * 0.03))
				end
			end
		end
		local earX = if backOnLeft then 4 else 3
		if style ~= "long" then
			put(px, w, earX, 4, mul(skin, 0.85))
		end
	end
	return px
end

local function torso(s: Skin, face: string): Pixels
	local w, h = Skins.faceSize("Torso", face)
	local shirt, pants, skin = rgb(s.shirt), rgb(s.pants), rgb(s.skin)
	local style = s.shirtStyle
	local px = canvas(w, h, shirt, 3)
	if face == "Top" then
		if style == "vest" or style == "plain" then
			put(px, w, 3, 1, skin)
			put(px, w, 4, 1, skin)
		end
		return px
	elseif face == "Bottom" then
		return canvas(w, h, pants, 4)
	end
	if style == "stripe" then
		for _, y in { 2, 5, 8 } do
			rows(px, w, y, y, mul(shirt, 0.7))
		end
	end
	if face == "Front" then
		if style == "hoodie" then
			for y = 1, 3 do
				put(px, w, 3, y, { 236, 236, 236 })
				put(px, w, 4, y, { 236, 236, 236 })
			end
			for y = 7, 9 do
				for x = 2, 5 do
					put(px, w, x, y, mul(shirt, 0.8))
				end
			end
		elseif style == "vest" then
			for y = 0, 9 do
				put(px, w, 3, y, { 236, 236, 236 })
				put(px, w, 4, y, { 236, 236, 236 })
			end
		elseif style == "plain" then
			put(px, w, 3, 0, skin)
			put(px, w, 4, 0, skin)
		end
	elseif face == "Back" and style == "hoodie" then
		rows(px, w, 0, 2, mul(shirt, 0.82))
	end
	rows(px, w, h - 2, h - 1, pants)
	rows(px, w, h - 2, h - 2, mul(pants, 0.75))
	return px
end

local function arm(s: Skin, face: string): Pixels
	local w, h = Skins.faceSize("Left Arm", face)
	local shirt, skin = rgb(s.shirt), rgb(s.skin)
	local style = s.shirtStyle
	local sleeveEnd = if style == "hoodie" then 10 elseif style == "vest" then -1 else 3
	if face == "Top" then
		return canvas(w, h, if sleeveEnd >= 0 then shirt else skin, 5)
	elseif face == "Bottom" then
		return canvas(w, h, skin, 6)
	end
	local px = canvas(w, h, skin, 7)
	if sleeveEnd >= 0 then
		rows(px, w, 0, sleeveEnd, shirt)
		if style == "stripe" then
			rows(px, w, 2, 2, mul(shirt, 0.7))
		end
	end
	return px
end

local function leg(s: Skin, face: string): Pixels
	local w, h = Skins.faceSize("Left Leg", face)
	local pants, shoes = rgb(s.pants), rgb(s.shoes)
	if face == "Top" then
		return canvas(w, h, pants, 8)
	elseif face == "Bottom" then
		return canvas(w, h, shoes, 9)
	end
	local px = canvas(w, h, pants, 10)
	rows(px, w, h - 2, h - 1, shoes)
	return px
end

-- 몸 부위 한 면의 픽셀
function Skins.face(skin: Skin, partName: string, face: string): (Pixels, number, number)
	local w, h = Skins.faceSize(partName, face)
	local px
	if partName == "Head" then
		px = head(skin, face)
	elseif partName == "Torso" then
		px = torso(skin, face)
	elseif string.find(partName, "Arm") then
		px = arm(skin, face)
	else
		px = leg(skin, face)
	end
	return px, w, h
end

-- 텍스처가 없을 때 쓰는 부위별 대표 색
function Skins.partColor(skin: Skin, partName: string): Color3
	if partName == "Head" then
		return Skins.color(skin.skin)
	elseif partName == "Torso" then
		return Skins.color(skin.shirt)
	elseif string.find(partName, "Arm") then
		return if skin.shirtStyle == "vest" then Skins.color(skin.skin) else Skins.color(skin.shirt)
	end
	return Skins.color(skin.pants)
end

return Skins
