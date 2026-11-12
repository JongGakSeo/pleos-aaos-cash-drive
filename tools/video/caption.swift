// Renders a caption or a full card as a PNG for the demo video (ffmpeg here has no drawtext).
// Usage:
//   swift tools/video/caption.swift caption <out.png> <width> "<line1>" ["<line2>"]
//     transparent PNG, dark pill with white text, two lines max, for ffmpeg overlay at the bottom
//   swift tools/video/caption.swift band <out.png> <width> <height> "<line1>" ["<line2>"]
//     opaque near-black strip under the screen: line 1 bold white, line 2 regular gray, centred
//   swift tools/video/caption.swift card <out.png> <width> <height> "<title>" ["<subline>" ...]
//     opaque card (Pleos light gray) with a large title and up to five sublines
import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

func font(_ size: CGFloat, weight: String) -> CTFont {
    let name = weight == "bold" ? "AppleSDGothicNeo-Bold" : "AppleSDGothicNeo-Regular"
    return CTFontCreateWithName(name as CFString, size, nil)
}

func line(_ text: String, _ f: CTFont, _ color: CGColor) -> CTLine {
    let attrs: [CFString: Any] = [kCTFontAttributeName: f, kCTForegroundColorAttributeName: color]
    return CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attrs as [NSAttributedString.Key: Any]))
}

func save(_ ctx: CGContext, _ path: String) {
    guard let image = ctx.makeImage(),
          let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        fputs("cannot write \(path)\n", stderr); exit(1)
    }
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
}

func context(_ w: Int, _ h: Int) -> CGContext {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    return CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                     bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
}

let args = CommandLine.arguments
guard args.count >= 5 else { fputs("usage: caption|card ...\n", stderr); exit(2) }
let mode = args[1], out = args[2]
let white = CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1)

if mode == "caption" {
    let width = Int(args[3])!
    let lines = Array(args[4...]).filter { !$0.isEmpty }
    let size: CGFloat = 44
    let f = font(size, weight: "bold")
    let ctLines = lines.map { line($0, f, white) }
    let textWidth = ctLines.map { CGFloat(CTLineGetTypographicBounds($0, nil, nil, nil)) }.max() ?? 0
    let padX: CGFloat = 40, padY: CGFloat = 22, gap: CGFloat = 12
    let pillW = textWidth + padX * 2
    let pillH = CGFloat(lines.count) * size * 1.25 + padY * 2 + CGFloat(max(0, lines.count - 1)) * gap
    let height = Int(pillH) + 2
    let ctx = context(width, height)
    ctx.clear(CGRect(x: 0, y: 0, width: width, height: height))
    let pill = CGRect(x: (CGFloat(width) - pillW) / 2, y: 1, width: pillW, height: pillH)
    ctx.setFillColor(CGColor(srgbRed: 0.19, green: 0.196, blue: 0.21, alpha: 0.92))
    ctx.addPath(CGPath(roundedRect: pill, cornerWidth: 18, cornerHeight: 18, transform: nil)); ctx.fillPath()
    var y = pill.maxY - padY - size
    for ct in ctLines {
        let w = CGFloat(CTLineGetTypographicBounds(ct, nil, nil, nil))
        ctx.textPosition = CGPoint(x: (CGFloat(width) - w) / 2, y: y)
        CTLineDraw(ct, ctx)
        y -= size * 1.25 + gap
    }
    save(ctx, out)
} else if mode == "band" {
    // The caption strip of the band layout: it takes the place of the AAOS dock under the screen, so the
    // words never sit on the app (the briefing theater draws its own captions at the stage's lower edge).
    let width = Int(args[3])!, height = Int(args[4])!
    let lines = Array(args[5...]).filter { !$0.isEmpty }.prefix(2)
    let ctx = context(width, height)
    ctx.setFillColor(CGColor(srgbRed: 0.055, green: 0.059, blue: 0.067, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    let gray = CGColor(srgbRed: 0.72, green: 0.733, blue: 0.761, alpha: 1)
    let styled: [(CTLine, CGFloat)] = lines.enumerated().map { i, text in
        i == 0 ? (line(text, font(34, weight: "bold"), white), 34) : (line(text, font(28, weight: "regular"), gray), 28)
    }
    let gap: CGFloat = 10
    let blockH = styled.reduce(CGFloat(0)) { $0 + $1.1 } + CGFloat(max(0, styled.count - 1)) * gap
    // CoreText baselines: start from the top of the centred block and step down by each line's size.
    var top = (CGFloat(height) + blockH) / 2
    for (ct, size) in styled {
        let w = CGFloat(CTLineGetTypographicBounds(ct, nil, nil, nil))
        ctx.textPosition = CGPoint(x: (CGFloat(width) - w) / 2, y: top - size * 0.86)
        CTLineDraw(ct, ctx)
        top -= size + gap
    }
    save(ctx, out)
} else if mode == "card" {
    let width = Int(args[3])!, height = Int(args[4])!
    let title = args[5]
    let subs = args.count > 6 ? Array(args[6...]) : []
    let ctx = context(width, height)
    ctx.setFillColor(CGColor(srgbRed: 0.969, green: 0.973, blue: 0.980, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    let ink = CGColor(srgbRed: 0.075, green: 0.078, blue: 0.09, alpha: 1)
    let sub = CGColor(srgbRed: 0.41, green: 0.416, blue: 0.447, alpha: 1)
    let titleLine = line(title, font(112, weight: "bold"), ink)
    let tw = CGFloat(CTLineGetTypographicBounds(titleLine, nil, nil, nil))
    // CARD_ICON: a square PNG (the app icon) set above the title, part of the centred block.
    var icon: CGImage? = nil
    if let path = ProcessInfo.processInfo.environment["CARD_ICON"], !path.isEmpty,
       let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) {
        icon = CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
    let iconSize: CGFloat = 168, iconGap: CGFloat = 48
    let iconH = icon == nil ? 0 : iconSize + iconGap
    let blockH = iconH + 112 + CGFloat(subs.count) * 54 + (subs.isEmpty ? 0 : 36)
    let top = (CGFloat(height) + blockH) / 2
    if let icon = icon {
        ctx.interpolationQuality = .high
        ctx.draw(icon, in: CGRect(x: (CGFloat(width) - iconSize) / 2, y: top - iconSize, width: iconSize, height: iconSize))
    }
    let baseline = top - iconH - 112
    var y = baseline
    ctx.textPosition = CGPoint(x: (CGFloat(width) - tw) / 2, y: y); CTLineDraw(titleLine, ctx)
    y -= 36 + 54
    for s in subs {
        let l = line(s, font(38, weight: "regular"), sub)
        let w = CGFloat(CTLineGetTypographicBounds(l, nil, nil, nil))
        ctx.textPosition = CGPoint(x: (CGFloat(width) - w) / 2, y: y); CTLineDraw(l, ctx)
        y -= 54
    }
    // amber hairline under the title, the app's one accent
    ctx.setFillColor(CGColor(srgbRed: 0.61, green: 0.43, blue: 0.10, alpha: 1))
    ctx.fill(CGRect(x: (CGFloat(width) - 160) / 2, y: baseline - 28, width: 160, height: 4))
    save(ctx, out)
} else {
    fputs("unknown mode \(mode)\n", stderr); exit(2)
}
