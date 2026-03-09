import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

class GithubUpdateService {
  static const String _owner = 'thumb2086';
  static const String _repo = 'Device-Linker';
  static const String _apiUrl = 'https://api.github.com/repos/$_owner/$_repo/releases/latest';
  static const String _releasesUrl = 'https://github.com/$_owner/$_repo/releases';
  static const String _latestReleaseUrl = '$_releasesUrl/latest';

  Future<void> checkForUpdates(
    BuildContext context, {
    required String title,
    required String descriptionTemplate,
    required String laterLabel,
    required String nowLabel,
    required String openFailedMessage,
  }) async {
    try {
      final response = await http.get(Uri.parse(_apiUrl));
      if (response.statusCode != 200) return;

      final data = jsonDecode(response.body);
      final String latestTagName = data['tag_name'] ?? ''; // e.g., "v1.5.0"
      final String htmlUrl = (data['html_url'] ?? '').toString().trim();
      final String releaseUrl = htmlUrl.isNotEmpty ? htmlUrl : _latestReleaseUrl;

      if (latestTagName.isEmpty) return;

      final packageInfo = await PackageInfo.fromPlatform();
      final String currentVersion = packageInfo.version; // e.g., "1.4.0"

      if (_isUpdateAvailable(currentVersion, latestTagName)) {
        if (!context.mounted) return;
        final description = descriptionTemplate.replaceAll('{1}', latestTagName);
        _showUpdateDialog(
          context,
          title: title,
          description: description,
          laterLabel: laterLabel,
          nowLabel: nowLabel,
          url: releaseUrl,
          openFailedMessage: openFailedMessage,
        );
      }
    } catch (e) {
      debugPrint('Update check failed: $e');
    }
  }

  bool _isUpdateAvailable(String current, String latest) {
    // Basic semver comparison
    // Normalizing: remove 'v' prefix if present
    String cleanCurrent = current.startsWith('v') ? current.substring(1) : current;
    String cleanLatest = latest.startsWith('v') ? latest.substring(1) : latest;

    List<int> currentParts = cleanCurrent.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    List<int> latestParts = cleanLatest.split('.').map((e) => int.tryParse(e) ?? 0).toList();

    // Pad with zeros if necessary
    while (currentParts.length < 3) {
      currentParts.add(0);
    }
    while (latestParts.length < 3) {
      latestParts.add(0);
    }

    for (int i = 0; i < 3; i++) {
      if (latestParts[i] > currentParts[i]) return true;
      if (latestParts[i] < currentParts[i]) return false;
    }

    return false;
  }

  void _showUpdateDialog(
    BuildContext context, {
    required String title,
    required String description,
    required String laterLabel,
    required String nowLabel,
    required String url,
    required String openFailedMessage,
  }) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: Text(description),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: Text(laterLabel),
          ),
          FilledButton(
            onPressed: () async {
              Navigator.pop(dialogContext);
              await _openUpdatePage(context, url, openFailedMessage);
            },
            child: Text(nowLabel),
          ),
        ],
      ),
    );
  }

  Future<void> _openUpdatePage(
    BuildContext context,
    String url,
    String openFailedMessage,
  ) async {
    final candidates = <Uri>[
      Uri.parse(url),
      Uri.parse(_latestReleaseUrl),
      Uri.parse(_releasesUrl),
    ];

    for (final uri in candidates) {
      try {
        if (await launchUrl(uri, mode: LaunchMode.externalApplication)) {
          return;
        }
      } catch (e) {
        debugPrint('External update launch failed for $uri: $e');
      }
    }

    for (final uri in candidates) {
      try {
        if (await launchUrl(uri, mode: LaunchMode.platformDefault)) {
          return;
        }
      } catch (e) {
        debugPrint('Fallback update launch failed for $uri: $e');
      }
    }

    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(openFailedMessage)),
    );
  }
}
