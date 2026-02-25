import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:app_links/app_links.dart';
import 'package:asn1lib/asn1lib.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:pointycastle/export.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:web3dart/crypto.dart' as web3crypto;
import 'package:web3dart/web3dart.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DeviceLinkerApp());
}

enum AppLanguage {
  system,
  zhTw,
  zhCn,
  en,
}

extension AppLanguageX on AppLanguage {
  String get tag {
    switch (this) {
      case AppLanguage.system:
        return 'system';
      case AppLanguage.zhTw:
        return 'zh-TW';
      case AppLanguage.zhCn:
        return 'zh-CN';
      case AppLanguage.en:
        return 'en';
    }
  }

  static AppLanguage fromTag(String raw) {
    switch (raw) {
      case 'zh-TW':
        return AppLanguage.zhTw;
      case 'zh-CN':
        return AppLanguage.zhCn;
      case 'en':
        return AppLanguage.en;
      default:
        return AppLanguage.system;
    }
  }
}

class DeviceLinkerApp extends StatefulWidget {
  const DeviceLinkerApp({super.key});

  @override
  State<DeviceLinkerApp> createState() => _DeviceLinkerAppState();
}

class _DeviceLinkerAppState extends State<DeviceLinkerApp> {
  static const String _languagePrefKey = 'dlinker.language';

  AppLanguage _language = AppLanguage.system;
  bool _isReady = false;

  @override
  void initState() {
    super.initState();
    _loadLanguage();
  }

  Future<void> _loadLanguage() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_languagePrefKey) ?? 'system';
    if (!mounted) {
      return;
    }
    setState(() {
      _language = AppLanguageX.fromTag(raw);
      _isReady = true;
    });
  }

  Future<void> _setLanguage(AppLanguage language) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_languagePrefKey, language.tag);
    if (!mounted) {
      return;
    }
    setState(() {
      _language = language;
    });
  }

  Locale? _resolveLocale() {
    switch (_language) {
      case AppLanguage.system:
        return null;
      case AppLanguage.zhTw:
        return const Locale('zh', 'TW');
      case AppLanguage.zhCn:
        return const Locale('zh', 'CN');
      case AppLanguage.en:
        return const Locale('en');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_isReady) {
      return MaterialApp(
        debugShowCheckedModeBanner: false,
        home: Scaffold(
          body: Center(
            child: const CircularProgressIndicator(),
          ),
        ),
      );
    }

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'D-Linker',
      locale: _resolveLocale(),
      supportedLocales: const [
        Locale('en'),
        Locale('zh', 'TW'),
        Locale('zh', 'CN'),
      ],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF00897B)),
        useMaterial3: true,
      ),
      home: DashboardScreen(
        language: _language,
        onLanguageChanged: _setLanguage,
      ),
    );
  }
}

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({
    required this.language,
    required this.onLanguageChanged,
    super.key,
  });

  final AppLanguage language;
  final Future<void> Function(AppLanguage language) onLanguageChanged;

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  final DLinkerApi _api = DLinkerApi();
  final KeyService _keyService = KeyService();
  final ContactRepository _contactRepository = ContactRepository();
  final NotificationService _notificationService = NotificationService.instance;

  StreamSubscription<Uri>? _deepLinkSub;
  Timer? _balanceTimer;

  String _derivedAddress = '';
  String _balance = '0.00';
  double _lastKnownBalance = 0.0;
  bool _isLoading = false;
  bool _isSyncingBalance = false;

  String? _pendingAuthSessionId;
  BetRequest? _pendingBetRequest;
  bool _isPromptOpen = false;

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  @override
  void dispose() {
    _deepLinkSub?.cancel();
    _balanceTimer?.cancel();
    _api.dispose();
    super.dispose();
  }

  Future<void> _bootstrap() async {
    try {
      await _notificationService.initialize();
      await _notificationService.requestPermissions();

      await _keyService.ensureKeyPair();
      final addr = await _keyService.getWalletAddress();
      final lastBalance = await AppStorage.getLastKnownBalance();

      if (mounted) {
        setState(() {
          _derivedAddress = addr;
          _lastKnownBalance = double.tryParse(lastBalance) ?? 0.0;
        });
      }

      await _syncBalance(notifyIfIncreased: false);
      _balanceTimer = Timer.periodic(
        const Duration(seconds: 15),
        (_) => _syncBalance(),
      );

      await _setupDeepLinks();
    } catch (e) {
      if (!mounted) {
        return;
      }
      _showSnack(
        AppStrings.tr(
          context,
          'failure_message',
          [e.toString()],
        ),
      );
    }
  }

  Future<void> _setupDeepLinks() async {
    try {
      final links = AppLinks();
      final dynamic initial = await (links as dynamic).getInitialLink();
      _handleRawPayload(initial?.toString());
      _deepLinkSub = links.uriLinkStream.listen(
        (uri) => _handleRawPayload(uri.toString()),
      );
    } catch (e) {
      debugPrint('Deep link setup failed: $e');
    }
  }

  Future<void> _handleRawPayload(String? raw) async {
    if (raw == null) {
      return;
    }
    final data = raw.trim();
    if (data.isEmpty) {
      return;
    }

    final sid = _extractSessionId(data);
    if (sid != null) {
      _pendingAuthSessionId = sid;
      _drainPromptQueue();
      return;
    }

    final bet = _extractBetRequest(data);
    if (bet != null) {
      _pendingBetRequest = bet;
      _drainPromptQueue();
      return;
    }

    final transferAddr = _extractAddress(data);
    if (transferAddr != null) {
      await _openTransferFlow(isMigration: false, initialAddress: transferAddr);
      return;
    }

    if (mounted) {
      _showSnack(AppStrings.tr(context, 'unknown_code'));
    }
  }

  void _drainPromptQueue() {
    if (!mounted || _isPromptOpen) {
      return;
    }

    if (_pendingAuthSessionId != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (!mounted || _isPromptOpen) {
          return;
        }
        final sid = _pendingAuthSessionId;
        if (sid == null) {
          return;
        }
        _pendingAuthSessionId = null;
        _isPromptOpen = true;
        await _showAuthConfirmDialog(sid);
        _isPromptOpen = false;
        _drainPromptQueue();
      });
      return;
    }

    if (_pendingBetRequest != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (!mounted || _isPromptOpen) {
          return;
        }
        final bet = _pendingBetRequest;
        if (bet == null) {
          return;
        }
        _pendingBetRequest = null;
        _isPromptOpen = true;
        await _showBetConfirmDialog(bet);
        _isPromptOpen = false;
        _drainPromptQueue();
      });
    }
  }

  Future<void> _runWithLoading(Future<void> Function() action) async {
    if (mounted) {
      setState(() {
        _isLoading = true;
      });
    }

    try {
      await action();
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _syncBalance({bool notifyIfIncreased = true}) async {
    if (_derivedAddress.isEmpty || _isSyncingBalance) {
      return;
    }

    _isSyncingBalance = true;
    try {
      final newBalance = await _api.syncBalance(_derivedAddress);
      final parsed = double.tryParse(newBalance) ?? 0.0;

      if (notifyIfIncreased && parsed > _lastKnownBalance) {
        final diff = parsed - _lastKnownBalance;
        await _notificationService.showBalanceNotification(
          amountDiff: diff,
          total: parsed,
        );
      }

      _lastKnownBalance = parsed;
      await AppStorage.setLastKnownBalance(newBalance);

      if (mounted) {
        setState(() {
          _balance = newBalance;
        });
      }
    } catch (e) {
      debugPrint('sync balance failed: $e');
    } finally {
      _isSyncingBalance = false;
    }
  }

  Future<void> _requestAirdrop() async {
    if (_derivedAddress.isEmpty) {
      return;
    }

    await _runWithLoading(() async {
      final publicKey = await _keyService.getPublicKeySpkiBase64();
      final signature = await _keyService.signData(
        _derivedAddress.trim().toLowerCase(),
      );

      await _api.requestAirdrop(
        walletAddress: _derivedAddress.trim().toLowerCase(),
        publicKey: publicKey,
        signature: signature,
      );

      if (mounted) {
        _showSnack(AppStrings.tr(context, 'airdrop_request_sent'));
      }

      await Future<void>.delayed(const Duration(seconds: 2));
      await _syncBalance();
    });
  }

  Future<void> _openTransferFlow({
    required bool isMigration,
    String initialAddress = '',
  }) async {
    if (!mounted) {
      return;
    }

    String destinationAddress = initialAddress.trim();

    while (destinationAddress.isEmpty) {
      final addressAction = await _showAddressInputDialog(
        isMigration: isMigration,
        preFilled: destinationAddress,
      );

      if (!mounted || addressAction == null) {
        return;
      }

      if (addressAction.type == AddressInputAction.confirm) {
        destinationAddress = addressAction.value.trim();
      } else if (addressAction.type == AddressInputAction.scan) {
        final scanned = await _showScannerDialog();
        if (scanned == null || scanned.trim().isEmpty) {
          return;
        }
        final addr = _extractAddress(scanned);
        if (addr == null) {
          await _handleRawPayload(scanned);
          return;
        }
        destinationAddress = addr;
      } else if (addressAction.type == AddressInputAction.contacts) {
        final selected = await Navigator.of(context).push<String>(
          MaterialPageRoute<String>(
            builder: (_) => ContactsScreen(
              selectionMode: true,
              repository: _contactRepository,
            ),
          ),
        );

        if (selected == null || selected.trim().isEmpty) {
          return;
        }
        destinationAddress = selected.trim();
      }
    }

    final defaultAmount = isMigration ? _balance : '10';
    final amount = await _showTransferDialog(
      toAddress: destinationAddress,
      isMigration: isMigration,
      amount: defaultAmount,
    );

    if (amount == null || amount.trim().isEmpty || !mounted) {
      return;
    }

    await _submitTransfer(destinationAddress, amount);
  }

  Future<void> _submitTransfer(String toAddress, String rawAmount) async {
    await _runWithLoading(() async {
      final cleanTo = toAddress.trim().toLowerCase().replaceFirst(RegExp(r'^0x'), '');
      var amount = rawAmount.trim();
      if (amount.endsWith('.0')) {
        amount = amount.substring(0, amount.length - 2);
      }

      final signMessage = 'transfer:$cleanTo:$amount';
      final signature = await _keyService.signData(signMessage);
      final publicKey = await _keyService.getPublicKeySpkiBase64();

      await _api.transfer(
        from: _derivedAddress.trim(),
        to: toAddress.trim(),
        amount: rawAmount.trim(),
        signature: signature,
        publicKey: publicKey,
      );

      if (mounted) {
        _showSnack(AppStrings.tr(context, 'transfer_success'));
      }

      await Future<void>.delayed(const Duration(seconds: 2));
      await _syncBalance();
    });
  }

  Future<void> _openHistory() async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (_) => HistoryScreen(
          api: _api,
          keyService: _keyService,
        ),
      ),
    );
  }

  Future<void> _openContacts() async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (_) => ContactsScreen(
          selectionMode: false,
          repository: _contactRepository,
        ),
      ),
    );
  }

  Future<void> _openGeneralScanner() async {
    final scanned = await _showScannerDialog();
    if (!mounted || scanned == null || scanned.trim().isEmpty) {
      return;
    }
    await _handleRawPayload(scanned);
  }

  Future<void> _showReceiveDialog() async {
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.tr(context, 'receive_address')),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (_derivedAddress.isNotEmpty)
              QrImageView(
                data: _derivedAddress,
                version: QrVersions.auto,
                size: 220,
                backgroundColor: Colors.white,
              ),
            const SizedBox(height: 12),
            SelectableText(
              _derivedAddress,
              textAlign: TextAlign.center,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppStrings.tr(context, 'close')),
          ),
        ],
      ),
    );
  }

  Future<void> _showSettingsDialog() async {
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.tr(context, 'settings')),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(AppStrings.tr(context, 'language_settings')),
            const SizedBox(height: 8),
            _languageTile(AppLanguage.system, AppStrings.tr(context, 'lang_auto')),
            _languageTile(AppLanguage.zhTw, AppStrings.tr(context, 'lang_zh_tw')),
            _languageTile(AppLanguage.zhCn, AppStrings.tr(context, 'lang_zh_cn')),
            _languageTile(AppLanguage.en, AppStrings.tr(context, 'lang_en')),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppStrings.tr(context, 'close')),
          ),
        ],
      ),
    );
  }

  Widget _languageTile(AppLanguage language, String title) {
    return InkWell(
      onTap: () async {
        await widget.onLanguageChanged(language);
        if (mounted) {
          Navigator.of(context).pop();
        }
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Row(
          children: [
            Expanded(child: Text(title)),
            if (widget.language == language)
              Icon(
                Icons.check,
                color: Theme.of(context).colorScheme.primary,
              ),
          ],
        ),
      ),
    );
  }

  Future<_AddressInputResult?> _showAddressInputDialog({
    required bool isMigration,
    required String preFilled,
  }) async {
    final controller = TextEditingController(text: preFilled);

    final result = await showDialog<_AddressInputResult>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          isMigration
              ? AppStrings.tr(context, 'migration')
              : AppStrings.tr(context, 'manual_address_input'),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: controller,
              decoration: InputDecoration(
                labelText: AppStrings.tr(context, 'address_placeholder'),
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 10),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                IconButton(
                  tooltip: AppStrings.tr(context, 'contacts'),
                  onPressed: () {
                    Navigator.of(context).pop(
                      const _AddressInputResult(
                        type: AddressInputAction.contacts,
                        value: '',
                      ),
                    );
                  },
                  icon: const Icon(Icons.contacts),
                ),
                IconButton(
                  tooltip: AppStrings.tr(context, 'scan'),
                  onPressed: () {
                    Navigator.of(context).pop(
                      const _AddressInputResult(
                        type: AddressInputAction.scan,
                        value: '',
                      ),
                    );
                  },
                  icon: const Icon(Icons.qr_code_scanner),
                ),
              ],
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppStrings.tr(context, 'cancel')),
          ),
          FilledButton(
            onPressed: () {
              Navigator.of(context).pop(
                _AddressInputResult(
                  type: AddressInputAction.confirm,
                  value: controller.text,
                ),
              );
            },
            child: Text(AppStrings.tr(context, 'confirm')),
          ),
        ],
      ),
    );

    controller.dispose();
    return result;
  }

  Future<String?> _showTransferDialog({
    required String toAddress,
    required bool isMigration,
    required String amount,
  }) async {
    final controller = TextEditingController(text: amount);

    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          isMigration
              ? AppStrings.tr(context, 'migration_title')
              : AppStrings.tr(context, 'send_symbol', [
                  AppStrings.tr(context, 'token_symbol'),
                ]),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              AppStrings.tr(context, 'to_address', [toAddress]),
              style: TextStyle(color: Theme.of(context).colorScheme.outline),
            ),
            const SizedBox(height: 8),
            if (isMigration)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  AppStrings.tr(context, 'migration_desc'),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            TextField(
              controller: controller,
              readOnly: isMigration,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(
                labelText: AppStrings.tr(context, 'amount'),
                border: const OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppStrings.tr(context, 'cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: Text(
              isMigration
                  ? AppStrings.tr(context, 'migration_confirm')
                  : AppStrings.tr(context, 'confirm_send'),
            ),
          ),
        ],
      ),
    );

    controller.dispose();
    return result;
  }

  Future<void> _showAuthConfirmDialog(String sessionId) async {
    if (!mounted) {
      return;
    }

    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.tr(context, 'auth_confirm_title')),
        content: Text(
          AppStrings.tr(
            context,
            'auth_confirm_desc',
            [sessionId, _derivedAddress],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppStrings.tr(context, 'cancel')),
          ),
          FilledButton(
            onPressed: () async {
              Navigator.of(context).pop();
              await _runWithLoading(() async {
                final pubKey = await _keyService.getPublicKeySpkiBase64();
                await _api.sendAuth(
                  sessionId: sessionId,
                  address: _derivedAddress,
                  publicKey: pubKey,
                );

                if (mounted) {
                  _showSnack(AppStrings.tr(context, 'auth_success_return'));
                }
              });
            },
            child: Text(AppStrings.tr(context, 'auth_confirm_button')),
          ),
        ],
      ),
    );
  }

  Future<void> _showBetConfirmDialog(BetRequest bet) async {
    if (!mounted) {
      return;
    }

    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.tr(context, 'bet_confirm_title')),
        content: Text(
          AppStrings.tr(
            context,
            'bet_confirm_desc',
            [
              'Coin Flip',
              bet.side,
              bet.amount,
              AppStrings.tr(context, 'token_symbol'),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppStrings.tr(context, 'cancel')),
          ),
          FilledButton(
            onPressed: () async {
              Navigator.of(context).pop();
              await _runWithLoading(() async {
                final signMessage = 'coinflip:${bet.side}:${bet.amount}';
                final signature = await _keyService.signData(signMessage);
                final pubKey = await _keyService.getPublicKeySpkiBase64();
                await _api.sendCoinFlip(
                  gameId: bet.gameId,
                  address: _derivedAddress,
                  side: bet.side,
                  amount: bet.amount,
                  signature: signature,
                  publicKey: pubKey,
                );
                if (mounted) {
                  _showSnack(AppStrings.tr(context, 'bet_success'));
                }
              });
            },
            child: Text(AppStrings.tr(context, 'bet_confirm_button')),
          ),
        ],
      ),
    );
  }

  Future<String?> _showScannerDialog() async {
    if (!_scannerSupported) {
      return _showManualCodeDialog();
    }

    return showDialog<String>(
      context: context,
      builder: (_) => const _ScannerDialog(),
    );
  }

  Future<String?> _showManualCodeDialog() async {
    final controller = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.tr(context, 'manual_code_entry')),
        content: TextField(
          controller: controller,
          decoration: InputDecoration(
            labelText: AppStrings.tr(context, 'manual_code_hint'),
            border: const OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppStrings.tr(context, 'cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: Text(AppStrings.tr(context, 'confirm')),
          ),
        ],
      ),
    );

    controller.dispose();
    return result;
  }

  bool get _scannerSupported {
    if (kIsWeb) {
      return true;
    }

    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
      case TargetPlatform.iOS:
      case TargetPlatform.macOS:
        return true;
      case TargetPlatform.fuchsia:
      case TargetPlatform.linux:
      case TargetPlatform.windows:
        return false;
    }
  }

  String? _extractSessionId(String raw) {
    final s = raw.trim();

    if (s.startsWith('session_')) {
      return s;
    }

    const prefix1 = 'dlinker:login:';
    if (s.toLowerCase().startsWith(prefix1)) {
      final value = s.substring(prefix1.length).trim();
      return value.isEmpty ? null : value;
    }

    const prefix2 = 'dlinker://login/';
    if (s.toLowerCase().startsWith(prefix2)) {
      final value = s.substring(prefix2.length).trim();
      return value.isEmpty ? null : value;
    }

    return null;
  }

  BetRequest? _extractBetRequest(String raw) {
    final s = raw.trim();
    const prefix = 'dlinker:coinflip:';
    if (!s.toLowerCase().startsWith(prefix)) {
      return null;
    }

    final parts = s.split(':');
    if (parts.length < 5) {
      return null;
    }

    return BetRequest(
      gameId: parts[2],
      side: parts[3],
      amount: parts[4],
    );
  }

  String? _extractAddress(String raw) {
    final regex = RegExp(r'0x[a-fA-F0-9]{40}');
    final match = regex.firstMatch(raw);
    if (match != null) {
      return match.group(0);
    }

    final trimmed = raw.trim();
    if (trimmed.length >= 42) {
      final last42 = trimmed.substring(trimmed.length - 42);
      if (RegExp(r'^0x[a-fA-F0-9]{40}$').hasMatch(last42)) {
        return last42;
      }
    }

    return null;
  }

  void _copyAddress(String value, BuildContext context) {
    Clipboard.setData(ClipboardData(text: value));
    _showSnack(AppStrings.tr(context, 'copy_success'));
  }

  void _showSnack(String message) {
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final tokenSymbol = AppStrings.tr(context, 'token_symbol');

    return Scaffold(
      appBar: AppBar(
        title: Text(
          AppStrings.tr(context, 'app_dashboard_title'),
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            onPressed: () async {
              await _runWithLoading(() => _syncBalance());
            },
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            onPressed: _showSettingsDialog,
            icon: const Icon(Icons.settings),
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _AssetCard(
                balance: _balance,
                address: _derivedAddress.isEmpty
                    ? AppStrings.tr(context, 'loading')
                    : _derivedAddress,
                tokenSymbol: tokenSymbol,
                onCopy: () => _copyAddress(_derivedAddress, context),
              ),
              const SizedBox(height: 20),
              Wrap(
                spacing: 10,
                runSpacing: 10,
                alignment: WrapAlignment.center,
                children: [
                  _ActionButton(
                    label: AppStrings.tr(context, 'receive'),
                    icon: Icons.account_balance_wallet,
                    onTap: _showReceiveDialog,
                  ),
                  _ActionButton(
                    label: AppStrings.tr(context, 'wallet_auth'),
                    icon: Icons.qr_code_scanner,
                    onTap: _openGeneralScanner,
                  ),
                  _ActionButton(
                    label: AppStrings.tr(context, 'transfer'),
                    icon: Icons.send,
                    onTap: () => _openTransferFlow(isMigration: false),
                  ),
                  _ActionButton(
                    label: AppStrings.tr(context, 'migration'),
                    icon: Icons.swap_horiz,
                    onTap: () => _openTransferFlow(isMigration: true),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: _isLoading ? null : _requestAirdrop,
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(52),
                ),
                child: _isLoading
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(
                        AppStrings.tr(
                          context,
                          'request_test_coins',
                          [tokenSymbol],
                        ),
                      ),
              ),
              const SizedBox(height: 16),
              _NavigationCard(
                title: AppStrings.tr(context, 'transaction_history'),
                icon: Icons.history,
                onTap: _openHistory,
              ),
              const SizedBox(height: 10),
              _NavigationCard(
                title: AppStrings.tr(context, 'contacts'),
                icon: Icons.contact_page,
                onTap: _openContacts,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AssetCard extends StatelessWidget {
  const _AssetCard({
    required this.balance,
    required this.address,
    required this.tokenSymbol,
    required this.onCopy,
  });

  final String balance;
  final String address;
  final String tokenSymbol;
  final VoidCallback onCopy;

  @override
  Widget build(BuildContext context) {
    return Card(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      child: Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          gradient: LinearGradient(
            colors: [
              Theme.of(context).colorScheme.primaryContainer,
              Theme.of(context).colorScheme.secondaryContainer,
            ],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              AppStrings.tr(context, 'my_assets'),
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 6),
            Text(
              AppStrings.tr(context, 'balance_format', [balance, tokenSymbol]),
              style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
            ),
            const SizedBox(height: 14),
            Text(
              AppStrings.tr(context, 'device_wallet_address'),
              style: Theme.of(context).textTheme.labelMedium,
            ),
            const SizedBox(height: 4),
            Row(
              children: [
                Expanded(
                  child: Text(
                    address,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
                IconButton(
                  onPressed: onCopy,
                  icon: const Icon(Icons.copy, size: 18),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ActionButton extends StatelessWidget {
  const _ActionButton({
    required this.label,
    required this.icon,
    required this.onTap,
  });

  final String label;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 88,
      child: Column(
        children: [
          FilledButton.tonal(
            onPressed: onTap,
            style: FilledButton.styleFrom(
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
              minimumSize: const Size(56, 56),
              padding: EdgeInsets.zero,
            ),
            child: Icon(icon),
          ),
          const SizedBox(height: 6),
          Text(
            label,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 11),
          ),
        ],
      ),
    );
  }
}

class _NavigationCard extends StatelessWidget {
  const _NavigationCard({
    required this.title,
    required this.icon,
    required this.onTap,
  });

  final String title;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        onTap: onTap,
        leading: Icon(icon),
        title: Text(
          title,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        trailing: const Icon(Icons.chevron_right),
      ),
    );
  }
}

class _ScannerDialog extends StatefulWidget {
  const _ScannerDialog();

  @override
  State<_ScannerDialog> createState() => _ScannerDialogState();
}

class _ScannerDialogState extends State<_ScannerDialog> {
  final MobileScannerController _controller = MobileScannerController();
  bool _handled = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _openManualCodeDialog() async {
    final controller = TextEditingController();
    final raw = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.tr(context, 'manual_code_entry')),
        content: TextField(
          controller: controller,
          decoration: InputDecoration(
            labelText: AppStrings.tr(context, 'manual_code_hint'),
            border: const OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(AppStrings.tr(context, 'cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: Text(AppStrings.tr(context, 'confirm')),
          ),
        ],
      ),
    );

    controller.dispose();
    if (!mounted || raw == null || raw.trim().isEmpty) {
      return;
    }

    Navigator.of(context).pop(raw.trim());
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      insetPadding: const EdgeInsets.all(20),
      child: SizedBox(
        width: 420,
        height: 480,
        child: Column(
          children: [
            Expanded(
              child: MobileScanner(
                controller: _controller,
                onDetect: (capture) {
                  if (_handled) {
                    return;
                  }
                  final value = capture.barcodes.firstOrNull?.rawValue;
                  if (value == null || value.trim().isEmpty) {
                    return;
                  }
                  _handled = true;
                  Navigator.of(context).pop(value.trim());
                },
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(10),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: _openManualCodeDialog,
                      child: Text(AppStrings.tr(context, 'manual_code_entry')),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: Text(AppStrings.tr(context, 'close')),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class HistoryScreen extends StatefulWidget {
  const HistoryScreen({
    required this.api,
    required this.keyService,
    super.key,
  });

  final DLinkerApi api;
  final KeyService keyService;

  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  final ScrollController _scrollController = ScrollController();

  List<HistoryItem> _history = <HistoryItem>[];
  String _walletAddress = '';
  int _page = 1;
  bool _hasMore = true;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _bootstrap();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _bootstrap() async {
    try {
      final address = await widget.keyService.getWalletAddress();
      if (!mounted) {
        return;
      }
      setState(() {
        _walletAddress = address;
      });
      await _refresh();
    } catch (e) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            AppStrings.tr(context, 'failure_message', [e.toString()]),
          ),
        ),
      );
    }
  }

  void _onScroll() {
    if (!_scrollController.hasClients || _isLoading || !_hasMore) {
      return;
    }

    final threshold = _scrollController.position.maxScrollExtent - 280;
    if (_scrollController.position.pixels >= threshold) {
      _loadMore();
    }
  }

  Future<void> _refresh() async {
    if (_walletAddress.isEmpty) {
      return;
    }

    setState(() {
      _isLoading = true;
    });

    try {
      final page = await widget.api.getHistory(walletAddress: _walletAddress, page: 1);
      if (!mounted) {
        return;
      }
      setState(() {
        _history = page.items;
        _page = 2;
        _hasMore = page.hasMore;
      });
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppStrings.tr(context, 'failure_message', [e.toString()]),
            ),
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _loadMore() async {
    if (_walletAddress.isEmpty || _isLoading || !_hasMore) {
      return;
    }

    setState(() {
      _isLoading = true;
    });

    try {
      final page = await widget.api.getHistory(walletAddress: _walletAddress, page: _page);
      if (!mounted) {
        return;
      }
      setState(() {
        _history = <HistoryItem>[..._history, ...page.items];
        _page += 1;
        _hasMore = page.hasMore;
      });
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppStrings.tr(context, 'failure_message', [e.toString()]),
            ),
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final symbol = AppStrings.tr(context, 'token_symbol');

    return Scaffold(
      appBar: AppBar(
        title: Text(
          AppStrings.tr(context, 'transaction_history'),
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            onPressed: _refresh,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: _history.isEmpty && !_isLoading
          ? Center(
              child: Text(
                AppStrings.tr(context, 'tx_no_history'),
                style: TextStyle(color: Theme.of(context).colorScheme.outline),
              ),
            )
          : ListView.separated(
              controller: _scrollController,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              itemCount: _history.length + (_isLoading ? 1 : 0),
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (context, index) {
                if (index >= _history.length) {
                  return const Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: Center(child: CircularProgressIndicator()),
                  );
                }

                final item = _history[index];
                final isSend = item.type.toLowerCase() == 'send';
                return ListTile(
                  contentPadding: const EdgeInsets.symmetric(vertical: 4),
                  leading: CircleAvatar(
                    backgroundColor: isSend
                        ? const Color(0xFFFFEBEE)
                        : const Color(0xFFE8F5E9),
                    child: Icon(
                      isSend ? Icons.north_east : Icons.south_west,
                      color: isSend ? Colors.red : const Color(0xFF4CAF50),
                    ),
                  ),
                  title: Text(
                    isSend
                        ? AppStrings.tr(context, 'tx_send')
                        : AppStrings.tr(context, 'tx_receive'),
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item.counterParty,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      Text(
                        item.date,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.outline,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                  trailing: Text(
                    '${isSend ? '-' : '+'} ${item.amount} $symbol',
                    style: TextStyle(
                      fontWeight: FontWeight.w800,
                      color: isSend ? null : const Color(0xFF4CAF50),
                    ),
                  ),
                );
              },
            ),
    );
  }
}

class ContactsScreen extends StatefulWidget {
  const ContactsScreen({
    required this.selectionMode,
    required this.repository,
    super.key,
  });

  final bool selectionMode;
  final ContactRepository repository;

  @override
  State<ContactsScreen> createState() => _ContactsScreenState();
}

class _ContactsScreenState extends State<ContactsScreen> {
  List<ContactModel> _contacts = <ContactModel>[];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadContacts();
  }

  Future<void> _loadContacts() async {
    final list = await widget.repository.getAllContacts();
    if (!mounted) {
      return;
    }
    setState(() {
      _contacts = list;
      _loading = false;
    });
  }

  Future<void> _addContactDialog() async {
    final nameController = TextEditingController();
    final addrController = TextEditingController();

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.tr(context, 'add_contact')),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameController,
              decoration: InputDecoration(
                labelText: AppStrings.tr(context, 'contact_name'),
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: addrController,
              decoration: InputDecoration(
                labelText: AppStrings.tr(context, 'wallet_address'),
                border: const OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(AppStrings.tr(context, 'cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(AppStrings.tr(context, 'confirm')),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      final name = nameController.text.trim();
      final address = addrController.text.trim();
      if (name.isNotEmpty && address.isNotEmpty) {
        await widget.repository.addContact(name: name, address: address);
        await _loadContacts();
      }
    }

    nameController.dispose();
    addrController.dispose();
  }

  Future<void> _deleteContact(ContactModel contact) async {
    await widget.repository.deleteContact(contact.id);
    await _loadContacts();
  }

  void _copy(String text) {
    Clipboard.setData(ClipboardData(text: text));
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(content: Text(AppStrings.tr(context, 'copy_success'))),
      );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.selectionMode
              ? AppStrings.tr(context, 'select_contact')
              : AppStrings.tr(context, 'contacts'),
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _addContactDialog,
        child: const Icon(Icons.add),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _contacts.isEmpty
              ? Center(
                  child: Text(
                    AppStrings.tr(context, 'no_contacts'),
                    style: TextStyle(color: Theme.of(context).colorScheme.outline),
                  ),
                )
              : ListView.separated(
                  itemCount: _contacts.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (context, index) {
                    final contact = _contacts[index];
                    return ListTile(
                      title: Text(
                        contact.name,
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      subtitle: Text(contact.address),
                      onTap: () {
                        if (widget.selectionMode) {
                          Navigator.of(context).pop(contact.address);
                        } else {
                          _copy(contact.address);
                        }
                      },
                      trailing: widget.selectionMode
                          ? null
                          : IconButton(
                              onPressed: () => _deleteContact(contact),
                              icon: const Icon(Icons.delete, color: Colors.red),
                            ),
                    );
                  },
                ),
    );
  }
}

class DLinkerApi {
  static const String _baseUrl = 'https://device-linker-api.vercel.app/api/';

  final http.Client _client = http.Client();

  void dispose() {
    _client.close();
  }

  Future<Map<String, dynamic>> _post(
    String endpoint,
    Map<String, dynamic> body,
  ) async {
    final response = await _client
        .post(
          Uri.parse('$_baseUrl$endpoint'),
          headers: <String, String>{
            'Content-Type': 'application/json; charset=utf-8',
            'User-Agent': 'D-Linker-Flutter-App',
          },
          body: jsonEncode(body),
        )
        .timeout(const Duration(seconds: 60));

    final text = response.body;
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw Exception('HTTP ${response.statusCode}: $text');
    }

    final decoded = jsonDecode(text);
    if (decoded is! Map<String, dynamic>) {
      throw Exception('Invalid API response format');
    }

    return decoded;
  }

  Future<void> sendAuth({
    required String sessionId,
    required String address,
    required String publicKey,
  }) async {
    final data = await _post('auth', <String, dynamic>{
      'sessionId': sessionId,
      'address': address.toLowerCase(),
      'publicKey': publicKey,
    });

    if (!(data['success'] == true)) {
      throw Exception((data['error'] ?? '授權失敗').toString());
    }
  }

  Future<void> sendCoinFlip({
    required String gameId,
    required String address,
    required String side,
    required String amount,
    required String signature,
    required String publicKey,
  }) async {
    final data = await _post('coinflip', <String, dynamic>{
      'gameId': gameId,
      'address': address.toLowerCase(),
      'side': side,
      'amount': amount,
      'signature': signature,
      'publicKey': publicKey,
    });

    if (!(data['success'] == true)) {
      throw Exception((data['error'] ?? '下注失敗').toString());
    }
  }

  Future<String> requestAirdrop({
    required String walletAddress,
    required String publicKey,
    required String signature,
  }) async {
    final data = await _post('airdrop', <String, dynamic>{
      'address': walletAddress.toLowerCase(),
      'publicKey': publicKey,
      'signature': signature,
    });

    if (!(data['success'] == true)) {
      throw Exception((data['error'] ?? '入金失敗').toString());
    }

    return (data['txHash'] ?? 'Success').toString();
  }

  Future<String> syncBalance(String walletAddress) async {
    final data = await _post('get-balance', <String, dynamic>{
      'address': walletAddress.toLowerCase(),
    });

    if (data['balance'] == null) {
      throw Exception((data['error'] ?? '查詢失敗').toString());
    }

    return data['balance'].toString();
  }

  Future<String> transfer({
    required String from,
    required String to,
    required String amount,
    required String signature,
    required String publicKey,
  }) async {
    final data = await _post('transfer', <String, dynamic>{
      'from': from.toLowerCase(),
      'to': to.toLowerCase(),
      'amount': amount,
      'signature': signature,
      'publicKey': publicKey,
    });

    if (!(data['success'] == true)) {
      throw Exception((data['error'] ?? '轉帳失敗').toString());
    }

    return data['txHash'].toString();
  }

  Future<HistoryPage> getHistory({
    required String walletAddress,
    int page = 1,
    int limit = 20,
  }) async {
    final data = await _post('history', <String, dynamic>{
      'address': walletAddress.toLowerCase(),
      'page': page,
      'limit': limit,
    });

    if (!(data['success'] == true)) {
      throw Exception((data['error'] ?? '無法取得紀錄').toString());
    }

    final List<dynamic> list = (data['history'] as List<dynamic>? ?? <dynamic>[]);
    final items = list
        .whereType<Map<String, dynamic>>()
        .map(HistoryItem.fromJson)
        .toList(growable: false);

    return HistoryPage(
      page: (data['page'] as num?)?.toInt() ?? 1,
      hasMore: data['hasMore'] == true,
      items: items,
    );
  }
}

class HistoryPage {
  const HistoryPage({
    required this.page,
    required this.hasMore,
    required this.items,
  });

  final int page;
  final bool hasMore;
  final List<HistoryItem> items;
}

class HistoryItem {
  const HistoryItem({
    required this.type,
    required this.amount,
    required this.counterParty,
    required this.timestamp,
    required this.date,
    required this.txHash,
    required this.blockNumber,
  });

  factory HistoryItem.fromJson(Map<String, dynamic> json) {
    return HistoryItem(
      type: (json['type'] ?? 'unknown').toString(),
      amount: (json['amount'] ?? '0').toString(),
      counterParty: (json['counterParty'] ?? '0x...').toString(),
      timestamp: (json['timestamp'] as num?)?.toInt() ?? 0,
      date: (json['date'] ?? '').toString(),
      txHash: (json['txHash'] ?? '').toString(),
      blockNumber: (json['blockNumber'] ?? '').toString(),
    );
  }

  final String type;
  final String amount;
  final String counterParty;
  final int timestamp;
  final String date;
  final String txHash;
  final String blockNumber;
}

class BetRequest {
  const BetRequest({
    required this.gameId,
    required this.side,
    required this.amount,
  });

  final String gameId;
  final String side;
  final String amount;
}

class KeyService {
  KeyService();

  static const String _storagePrivateKey = 'dlinker.private_key.hex.v1';

  final FlutterSecureStorage _storage = const FlutterSecureStorage();
  final ECDomainParameters _domain = ECDomainParameters('secp256k1');

  Future<void> ensureKeyPair() async {
    await _getOrCreatePrivateKey();
  }

  Future<String> getWalletAddress() async {
    final privateKey = await _getOrCreatePrivateKey();
    final pub = _derivePublicPoint(privateKey);
    final x = _bigIntTo32(pub.x!.toBigInteger()!);
    final y = _bigIntTo32(pub.y!.toBigInteger()!);

    final uncompressedNoPrefix = Uint8List.fromList(<int>[...x, ...y]);
    final hash = web3crypto.keccak256(uncompressedNoPrefix);
    final addressHex = web3crypto.bytesToHex(
      Uint8List.fromList(hash.sublist(hash.length - 20)),
      include0x: true,
    );

    return EthereumAddress.fromHex(addressHex).hexEip55;
  }

  Future<String> getPublicKeySpkiBase64() async {
    final privateKey = await _getOrCreatePrivateKey();
    final pub = _derivePublicPoint(privateKey);

    final x = _bigIntTo32(pub.x!.toBigInteger()!);
    final y = _bigIntTo32(pub.y!.toBigInteger()!);
    final uncompressedWithPrefix = Uint8List.fromList(<int>[0x04, ...x, ...y]);

    final algorithm = ASN1Sequence()
      ..add(ASN1ObjectIdentifier.fromIdentifierString('1.2.840.10045.2.1'))
      ..add(ASN1ObjectIdentifier.fromIdentifierString('1.3.132.0.10'));

    final top = ASN1Sequence()
      ..add(algorithm)
      ..add(ASN1BitString(stringValues: uncompressedWithPrefix));

    return base64Encode(top.encodedBytes);
  }

  Future<String> signData(String message) async {
    final privateKey = await _getOrCreatePrivateKey();
    final ecPrivate = ECPrivateKey(privateKey, _domain);

    final digest = SHA256Digest().process(Uint8List.fromList(utf8.encode(message)));
    final signer = ECDSASigner(null, HMac(SHA256Digest(), 64));
    signer.init(true, PrivateKeyParameter<ECPrivateKey>(ecPrivate));

    final signature = signer.generateSignature(digest) as ECSignature;

    final der = ASN1Sequence()
      ..add(ASN1Integer(signature.r))
      ..add(ASN1Integer(signature.s));

    return base64Encode(der.encodedBytes);
  }

  Future<BigInt> _getOrCreatePrivateKey() async {
    final stored = await _storage.read(key: _storagePrivateKey);
    if (stored != null && stored.isNotEmpty) {
      return BigInt.parse(stored, radix: 16);
    }

    final created = _generateValidPrivateKey();
    await _storage.write(key: _storagePrivateKey, value: created.toRadixString(16));
    return created;
  }

  ECPoint _derivePublicPoint(BigInt privateKey) {
    return _domain.G * privateKey;
  }

  BigInt _generateValidPrivateKey() {
    final random = Random.secure();
    final max = _domain.n;

    while (true) {
      final bytes = Uint8List(32);
      for (var i = 0; i < bytes.length; i++) {
        bytes[i] = random.nextInt(256);
      }
      final d = _bytesToBigInt(bytes);
      if (d > BigInt.zero && d < max) {
        return d;
      }
    }
  }

  Uint8List _bigIntTo32(BigInt value) {
    final bytes = <int>[];
    var v = value;
    while (v > BigInt.zero) {
      bytes.insert(0, (v & BigInt.from(0xff)).toInt());
      v = v >> 8;
    }
    while (bytes.length < 32) {
      bytes.insert(0, 0);
    }
    return Uint8List.fromList(bytes.take(32).toList(growable: false));
  }

  BigInt _bytesToBigInt(Uint8List bytes) {
    var result = BigInt.zero;
    for (final b in bytes) {
      result = (result << 8) | BigInt.from(b);
    }
    return result;
  }
}

class NotificationService {
  NotificationService._();

  static final NotificationService instance = NotificationService._();

  final FlutterLocalNotificationsPlugin _plugin = FlutterLocalNotificationsPlugin();
  bool _initialized = false;

  Future<void> initialize() async {
    if (_initialized) {
      return;
    }

    const androidInit = AndroidInitializationSettings('@mipmap/ic_launcher');
    const iosInit = DarwinInitializationSettings();

    const initSettings = InitializationSettings(
      android: androidInit,
      iOS: iosInit,
      macOS: iosInit,
    );

    await _plugin.initialize(initSettings);
    _initialized = true;
  }

  Future<void> requestPermissions() async {
    final androidImpl =
        _plugin.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
    await androidImpl?.requestNotificationsPermission();

    final iosImpl = _plugin.resolvePlatformSpecificImplementation<IOSFlutterLocalNotificationsPlugin>();
    await iosImpl?.requestPermissions(alert: true, badge: true, sound: true);

    final macImpl =
        _plugin.resolvePlatformSpecificImplementation<MacOSFlutterLocalNotificationsPlugin>();
    await macImpl?.requestPermissions(alert: true, badge: true, sound: true);
  }

  Future<void> showBalanceNotification({
    required double amountDiff,
    required double total,
  }) async {
    const androidDetails = AndroidNotificationDetails(
      'balance_alerts',
      '餘額變動提醒',
      channelDescription: '當帳戶收到代幣時發送通知',
      importance: Importance.defaultImportance,
      priority: Priority.defaultPriority,
    );

    const iosDetails = DarwinNotificationDetails();

    const details = NotificationDetails(
      android: androidDetails,
      iOS: iosDetails,
      macOS: iosDetails,
    );

    final title = '代幣入帳通知';
    final body =
        '您收到了 ${amountDiff.toStringAsFixed(2)} 個代幣！目前餘額：${total.toStringAsFixed(2)}';

    await _plugin.show(
      DateTime.now().millisecondsSinceEpoch ~/ 1000,
      title,
      body,
      details,
    );
  }
}

class AppStorage {
  static const String _lastKnownBalanceKey = 'dlinker.last_known_balance';
  static const String _contactsKey = 'dlinker.contacts';

  static Future<String> getLastKnownBalance() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_lastKnownBalanceKey) ?? '0.0';
  }

  static Future<void> setLastKnownBalance(String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_lastKnownBalanceKey, value);
  }

  static Future<List<ContactModel>> getContacts() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_contactsKey);
    if (raw == null || raw.isEmpty) {
      return <ContactModel>[];
    }

    final decoded = jsonDecode(raw);
    if (decoded is! List<dynamic>) {
      return <ContactModel>[];
    }

    return decoded
        .whereType<Map<String, dynamic>>()
        .map(ContactModel.fromJson)
        .toList(growable: false);
  }

  static Future<void> saveContacts(List<ContactModel> contacts) async {
    final prefs = await SharedPreferences.getInstance();
    final payload = contacts.map((e) => e.toJson()).toList(growable: false);
    await prefs.setString(_contactsKey, jsonEncode(payload));
  }
}

class ContactRepository {
  Future<List<ContactModel>> getAllContacts() async {
    final list = await AppStorage.getContacts();
    final sorted = List<ContactModel>.from(list)
      ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    return sorted;
  }

  Future<void> addContact({required String name, required String address}) async {
    final list = await AppStorage.getContacts();
    final maxId = list.fold<int>(0, (prev, c) => max(prev, c.id));
    final next = <ContactModel>[
      ...list,
      ContactModel(id: maxId + 1, name: name, address: address),
    ];
    await AppStorage.saveContacts(next);
  }

  Future<void> deleteContact(int id) async {
    final list = await AppStorage.getContacts();
    final next = list.where((c) => c.id != id).toList(growable: false);
    await AppStorage.saveContacts(next);
  }
}

class ContactModel {
  const ContactModel({
    required this.id,
    required this.name,
    required this.address,
  });

  factory ContactModel.fromJson(Map<String, dynamic> json) {
    return ContactModel(
      id: (json['id'] as num?)?.toInt() ?? 0,
      name: (json['name'] ?? '').toString(),
      address: (json['address'] ?? '').toString(),
    );
  }

  final int id;
  final String name;
  final String address;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'id': id,
      'name': name,
      'address': address,
    };
  }
}

enum AddressInputAction {
  confirm,
  scan,
  contacts,
}

class _AddressInputResult {
  const _AddressInputResult({
    required this.type,
    required this.value,
  });

  final AddressInputAction type;
  final String value;
}

class AppStrings {
  static const Map<String, Map<String, String>> _data = {
    'en': {
      'app_dashboard_title': 'D-Linker Dashboard',
      'token_symbol': 'ZHIXI',
      'loading': 'Loading...',
      'my_assets': 'My Assets',
      'balance_format': '{1} {2}',
      'device_wallet_address': 'Device Wallet Address',
      'copy_success': 'Copied successfully',
      'receive': 'Receive',
      'wallet_auth': 'Wallet Auth',
      'scan': 'Scan',
      'transfer': 'Transfer',
      'migration': 'Device Migration',
      'request_test_coins': 'Get 100 {1} Test Coins',
      'airdrop_request_sent': 'Airdrop request sent',
      'failure_message': 'Failure: {1}',
      'manual_address_input': 'Enter Address Manually',
      'address_placeholder': '0x...',
      'cancel': 'Cancel',
      'confirm': 'Confirm',
      'send_symbol': 'Send {1}',
      'to_address': 'To: {1}',
      'amount': 'Amount',
      'confirm_send': 'Confirm Send',
      'transfer_success': 'Transfer successful!',
      'receive_address': 'Receive Address',
      'close': 'Close',
      'migration_title': 'Full Device Migration',
      'migration_desc':
          'Since the private key is stored in a hardware security module and cannot be exported, please transfer all assets to the new device address when changing devices.',
      'migration_confirm': 'Confirm Transfer All Balance',
      'settings': 'Settings',
      'language_settings': 'Language Settings',
      'lang_auto': 'System Default',
      'lang_zh_tw': '繁體中文',
      'lang_zh_cn': '简体中文',
      'lang_en': 'English',
      'transaction_history': 'Transaction History',
      'tx_send': 'Send',
      'tx_receive': 'Receive',
      'tx_no_history': 'No transaction history yet',
      'auth_confirm_title': 'Auth Login Request',
      'auth_confirm_desc': 'Web requests to link your wallet.\n\nSession ID: {1}\nAddress: {2}',
      'auth_confirm_button': 'Confirm Authorization',
      'auth_success_return': 'Authorization succeeded, return to web page',
      'manual_code_entry': 'Enter Auth Code',
      'manual_code_hint': 'Paste session_xxx or dlinker:login:xxx',
      'manual_code_error': 'Invalid auth code format',
      'bet_confirm_title': 'Bet Signature Request',
      'bet_confirm_desc': 'You are placing a {1} bet.\n\nSide: {2}\nAmount: {3} {4}',
      'bet_confirm_button': 'Confirm Bet and Sign',
      'bet_success': 'Bet request submitted',
      'contacts': 'Contacts',
      'select_contact': 'Select Contact',
      'no_contacts': 'No contacts yet',
      'add_contact': 'Add Contact',
      'contact_name': 'Name',
      'wallet_address': 'Wallet Address',
      'unknown_code': 'Unsupported QR/deep-link content',
    },
    'zh_TW': {
      'app_dashboard_title': 'D-Linker 儀表板',
      'token_symbol': '子熙幣',
      'loading': '載入中...',
      'my_assets': '我的資產',
      'balance_format': '{1} {2}',
      'device_wallet_address': '設備錢包地址',
      'copy_success': '複製成功',
      'receive': '收款',
      'wallet_auth': '錢包授權',
      'scan': '掃描',
      'transfer': '轉帳',
      'migration': '設備轉移',
      'request_test_coins': '領取 100 {1} 測試幣',
      'airdrop_request_sent': '入金請求已送出',
      'failure_message': '失敗: {1}',
      'manual_address_input': '手動輸入地址',
      'address_placeholder': '0x...',
      'cancel': '取消',
      'confirm': '確定',
      'send_symbol': '發送 {1}',
      'to_address': '至: {1}',
      'amount': '金額',
      'confirm_send': '確認發送',
      'transfer_success': '轉帳成功！',
      'receive_address': '收款地址',
      'close': '關閉',
      'migration_title': '全額設備轉移',
      'migration_desc': '由於私鑰儲存於硬體安全模組中無法匯出，若要更換設備，請將所有資產轉移至新設備的地址。',
      'migration_confirm': '確認轉移全部餘額',
      'settings': '設定',
      'language_settings': '語言設定',
      'lang_auto': '跟隨系統',
      'lang_zh_tw': '繁體中文',
      'lang_zh_cn': '簡體中文',
      'lang_en': 'English',
      'transaction_history': '交易紀錄',
      'tx_send': '轉出',
      'tx_receive': '轉入',
      'tx_no_history': '目前尚無交易紀錄',
      'auth_confirm_title': '授權登入請求',
      'auth_confirm_desc': '網頁端請求連結您的錢包。\n\nSession ID: {1}\n地址: {2}',
      'auth_confirm_button': '確認授權',
      'auth_success_return': '授權成功，可返回網頁',
      'manual_code_entry': '輸入授權碼',
      'manual_code_hint': '貼上 session_xxx 或 dlinker:login:xxx',
      'manual_code_error': '授權碼格式錯誤',
      'bet_confirm_title': '下注簽名請求',
      'bet_confirm_desc': '您正在進行 {1} 遊戲下注。\n\n選擇: {2}\n金額: {3} {4}',
      'bet_confirm_button': '確認下注並簽名',
      'bet_success': '下注請求已送出',
      'contacts': '通訊錄',
      'select_contact': '選擇聯絡人',
      'no_contacts': '目前尚無聯絡人',
      'add_contact': '新增聯絡人',
      'contact_name': '姓名',
      'wallet_address': '錢包地址',
      'unknown_code': '不支援的 QR/連結格式',
    },
    'zh_CN': {
      'app_dashboard_title': 'D-Linker 仪表板',
      'token_symbol': '子熙币',
      'loading': '加载中...',
      'my_assets': '我的资产',
      'balance_format': '{1} {2}',
      'device_wallet_address': '设备钱包地址',
      'copy_success': '复制成功',
      'receive': '收款',
      'wallet_auth': '钱包授权',
      'scan': '扫描',
      'transfer': '转账',
      'migration': '设备转移',
      'request_test_coins': '领取 100 {1} 测试币',
      'airdrop_request_sent': '入金请求已发送',
      'failure_message': '失败: {1}',
      'manual_address_input': '手动输入地址',
      'address_placeholder': '0x...',
      'cancel': '取消',
      'confirm': '确定',
      'send_symbol': '发送 {1}',
      'to_address': '至: {1}',
      'amount': '金额',
      'confirm_send': '确认发送',
      'transfer_success': '转账成功！',
      'receive_address': '收款地址',
      'close': '关闭',
      'migration_title': '全额设备转移',
      'migration_desc': '由于私钥存储于硬件安全模块中无法导出，若要更换设备，请将所有资产转移至新设备的地址。',
      'migration_confirm': '确认转移全部余额',
      'settings': '设置',
      'language_settings': '语言设置',
      'lang_auto': '跟随系统',
      'lang_zh_tw': '繁體中文',
      'lang_zh_cn': '简体中文',
      'lang_en': 'English',
      'transaction_history': '交易纪录',
      'tx_send': '转出',
      'tx_receive': '转入',
      'tx_no_history': '目前尚无交易纪录',
      'auth_confirm_title': '授权登录请求',
      'auth_confirm_desc': '网页端请求连接您的钱包。\n\nSession ID: {1}\n地址: {2}',
      'auth_confirm_button': '确认授权',
      'auth_success_return': '授权成功，可返回网页',
      'manual_code_entry': '输入授权码',
      'manual_code_hint': '粘贴 session_xxx 或 dlinker:login:xxx',
      'manual_code_error': '授权码格式错误',
      'bet_confirm_title': '下注签名请求',
      'bet_confirm_desc': '您正在进行 {1} 游戏下注。\n\n选择: {2}\n金额: {3} {4}',
      'bet_confirm_button': '确认下注并签名',
      'bet_success': '下注请求已发送',
      'contacts': '通讯录',
      'select_contact': '选择联系人',
      'no_contacts': '目前尚无联系人',
      'add_contact': '新增联系人',
      'contact_name': '姓名',
      'wallet_address': '钱包地址',
      'unknown_code': '不支持的 QR/链接格式',
    },
  };

  static String tr(BuildContext context, String key, [List<String> args = const []]) {
    final locale = Localizations.localeOf(context);
    final langKey = _localeToKey(locale);
    final table = _data[langKey] ?? _data['en']!;
    var value = table[key] ?? _data['en']![key] ?? key;

    for (var i = 0; i < args.length; i++) {
      value = value.replaceAll('{${i + 1}}', args[i]);
    }

    return value;
  }

  static String _localeToKey(Locale locale) {
    if (locale.languageCode.toLowerCase() == 'zh') {
      final country = (locale.countryCode ?? '').toUpperCase();
      final script = (locale.scriptCode ?? '').toLowerCase();
      if (country == 'CN' || script == 'hans') {
        return 'zh_CN';
      }
      return 'zh_TW';
    }
    return 'en';
  }
}

extension _FirstOrNullExt<E> on List<E> {
  E? get firstOrNull => isEmpty ? null : first;
}
