// Encrypted-backup cross-platform oracle. Builds authentic iOS v3 backup
// blobs (same PBKDF2-SHA256 + AES-256-GCM + ML-DSA-65 path as
// iCloudBackup.swift) by reusing the real iOS PBKDF2.swift + BIP39.swift +
// CryptoKit, so the Kotlin EncryptedBackup.decrypt is proven to open an
// iPhone-made backup. Salt + nonce are fixed per vector for reproducibility;
// the ML-DSA signature is hedged (the verifier just checks it).
//
// Build + run (writes the test asset):
//   swiftc -O -o /tmp/backuporacle \
//     android-sdk-musnad/tools/backup_oracle/main.swift \
//     ios-app-maknoon/Maknoon/PBKDF2.swift \
//     ios-app-maknoon/Maknoon/BIP39.swift
//   /tmp/backuporacle > android-sdk-musnad/musnad-sdk/src/androidTest/assets/backup.kat.json

import Foundation
import CryptoKit

final class LogStore { // shim for the symbol BIP39.swift references
    static let shared = LogStore()
    func error(_ category: String, _ message: String) {
        FileHandle.standardError.write(Data("[\(category)] \(message)\n".utf8))
    }
}

func b64(_ d: Data) -> String { d.base64EncodedString() }
func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
func bytes(_ h: String) -> Data {
    var out = Data(); var i = h.startIndex
    while i < h.endIndex { let j = h.index(i, offsetBy: 2); out.append(UInt8(h[i..<j], radix: 16)!); i = j }
    return out
}

struct Case { let passphrase: String; let entropyHex: String; let plaintext: String; let saltByte: UInt8; let nonceByte: UInt8 }
let cases = [
    Case(passphrase: "correct horse battery staple", entropyHex: String(repeating: "07", count: 32),
         plaintext: "maknoon-backup-kat-payload-v1", saltByte: 0xA1, nonceByte: 0xB2),
    Case(passphrase: "пароль🔐", entropyHex: (0..<32).map { String(format: "%02x", $0) }.joined(),
         plaintext: "{\"v\":4,\"note\":\"unicode passphrase\"}", saltByte: 0x5C, nonceByte: 0x3D),
]

var vectors: [[String: Any]] = []
for c in cases {
    let entropy = bytes(c.entropyHex)
    let plaintext = Data(c.plaintext.utf8)
    let salt = Data(repeating: c.saltByte, count: 16)
    let nonceData = Data(repeating: c.nonceByte, count: 12)

    let key = try PBKDF2.derive(
        password: Data(c.passphrase.precomposedStringWithCompatibilityMapping.utf8),
        salt: salt, iterations: 600_000, hash: .sha256, outputLength: 32)
    let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key),
                                  nonce: AES.GCM.Nonce(data: nonceData))
    let combined = sealed.combined!

    let words = BIP39.mnemonicFromSeed(entropy)
    let mldsaSeed = try BIP39.derivedSeed(mnemonic: words.joined(separator: " "), passphrase: c.passphrase).prefix(32)
    let sk = try MLDSA65.PrivateKey(seedRepresentation: Data(mldsaSeed), publicKey: nil)
    let masterPk = sk.publicKey.rawRepresentation
    let signature = try sk.signature(for: combined)

    let blob: [String: Any] = [
        "v": 3, "kdf": "pbkdf2-sha256", "iter": 600_000,
        "salt": b64(salt), "combined": b64(combined),
        "sigAlg": "ML-DSA-65", "masterPk": b64(masterPk), "signature": b64(signature),
    ]
    vectors.append([
        "passphrase": c.passphrase,
        "entropyHex": c.entropyHex,
        "plaintextHex": hex(plaintext),
        "blob": blob,
    ])
}

let out: [String: Any] = ["source": "iOS PBKDF2.swift + BIP39.swift + CryptoKit", "vectors": vectors]
let data = try JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys])
FileHandle.standardOutput.write(data)
FileHandle.standardOutput.write(Data("\n".utf8))
