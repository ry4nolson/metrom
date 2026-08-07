#!/usr/bin/env ruby
# Generates iosApp/Metrom.xcodeproj for the Metrom SwiftUI app + MetromShared framework.
require 'xcodeproj'
require 'fileutils'

ROOT = File.expand_path('..', __dir__)
IOS = File.join(ROOT, 'iosApp')
PROJECT_PATH = File.join(IOS, 'Metrom.xcodeproj')
SOURCES = File.join(IOS, 'Metrom')

FileUtils.rm_rf(PROJECT_PATH)

project = Xcodeproj::Project.new(PROJECT_PATH)
target = project.new_target(:application, 'Metrom', :ios, '16.0')
target.product_type = 'com.apple.product-type.application'

main_group = project.main_group
# Group path is relative to project (iosApp/)
metrom_group = main_group.new_group('Metrom', 'Metrom')

Dir[File.join(SOURCES, '*.swift')].sort.each do |path|
  name = File.basename(path)
  ref = metrom_group.new_file(name) # relative to group path Metrom/
  target.source_build_phase.add_file_reference(ref)
end

# Folder reference keeps tones/chug|kick structure in the app bundle (must NOT be named
# "Resources" — that makes codesign treat the .app as a broken macOS bundle).
tones_ref = metrom_group.new_file('Resources/tones')
tones_ref.name = 'tones'
tones_ref.last_known_file_type = 'folder'
tones_ref.include_in_index = '0'
target.resources_build_phase.add_file_reference(tones_ref)

metrom_group.new_file('Info.plist')

phase = project.new(Xcodeproj::Project::Object::PBXShellScriptBuildPhase)
phase.name = 'Compile MetromShared'
phase.shell_script = <<~'SCRIPT'
  set -euo pipefail
  export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
  cd "$SRCROOT/.."
  FRAME_SIM="$SRCROOT/../shared/build/bin/iosSimulatorArm64/debugFramework/MetromShared.framework"
  FRAME_DEV="$SRCROOT/../shared/build/bin/iosArm64/debugFramework/MetromShared.framework"
  if [[ "${PLATFORM_NAME:-}" == *simulator* && -d "$FRAME_SIM" ]]; then
    echo "Using prebuilt simulator MetromShared.framework"
    exit 0
  fi
  if [[ "${PLATFORM_NAME:-}" != *simulator* && -d "$FRAME_DEV" ]]; then
    echo "Using prebuilt device MetromShared.framework"
    exit 0
  fi
  ./gradlew -p . :shared:embedAndSignAppleFrameworkForXcode
SCRIPT
phase.shell_path = '/bin/bash'
phase.always_out_of_date = '1'
target.build_phases.delete(phase)
target.build_phases.unshift(phase)

shared_settings = {
  'PRODUCT_BUNDLE_IDENTIFIER' => 'com.metrom.app',
  'PRODUCT_NAME' => 'Metrom',
  'INFOPLIST_FILE' => 'Metrom/Info.plist',
  'GENERATE_INFOPLIST_FILE' => 'NO',
  'SWIFT_VERSION' => '5.0',
  'TARGETED_DEVICE_FAMILY' => '1',
  'IPHONEOS_DEPLOYMENT_TARGET' => '16.0',
  'SDKROOT' => 'iphoneos',
  'CODE_SIGN_STYLE' => 'Automatic',
  'DEVELOPMENT_TEAM' => 'M3C8F32WA5',
  'CODE_SIGN_IDENTITY' => 'Apple Development',
  'FRAMEWORK_SEARCH_PATHS' => '$(inherited) "$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)" "$(SRCROOT)/../shared/build/bin/iosSimulatorArm64/debugFramework" "$(SRCROOT)/../shared/build/bin/iosArm64/debugFramework"',
  'OTHER_LDFLAGS' => '$(inherited) -framework MetromShared',
  'ENABLE_PREVIEWS' => 'NO',
  'ENABLE_DEBUG_DYLIB' => 'NO',
  'CURRENT_PROJECT_VERSION' => '1',
  'MARKETING_VERSION' => '1.0',
  'LD_RUNPATH_SEARCH_PATHS' => '$(inherited) @executable_path/Frameworks',
  'ALWAYS_SEARCH_USER_PATHS' => 'NO',
  'CLANG_ENABLE_MODULES' => 'YES',
}

target.build_configurations.each do |config|
  shared_settings.each { |k, v| config.build_settings[k] = v }
end

# Embed MetromShared.framework into the app bundle for runtime loading
embed = project.new(Xcodeproj::Project::Object::PBXCopyFilesBuildPhase)
embed.name = 'Embed Frameworks'
embed.dst_subfolder_spec = '10' # Frameworks
embed.dst_path = ''
frame_path = File.join(ROOT, 'shared/build/bin/iosSimulatorArm64/debugFramework/MetromShared.framework')
frame_ref = main_group.new_file(frame_path)
embed_build_file = embed.add_file_reference(frame_ref)
embed_build_file.settings = { 'ATTRIBUTES' => ['CodeSignOnCopy', 'RemoveHeadersOnCopy'] }
target.build_phases << embed
# Also link explicitly (OTHER_LDFLAGS already has -framework)
target.frameworks_build_phase.add_file_reference(frame_ref)

project.build_configurations.each do |config|
  config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] = '16.0'
  config.build_settings['SDKROOT'] = 'iphoneos'
end

project.save
puts "Wrote #{PROJECT_PATH}"
puts "Swift: #{Dir[File.join(SOURCES, '*.swift')].size}, tones folder reference added"
